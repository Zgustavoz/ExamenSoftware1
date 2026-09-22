package com.diagramas.platform.support.xmi;

import static com.diagramas.platform.common.util.Json.text;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.design.service.DiagramOperationApplier;
import com.diagramas.platform.design.service.LayoutUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Implementación con JAXP/DOM (sin EMF). Mapeo según 10.4. Al importar se deshabilitan DTD y entidades
 * externas (XXE) y se acota el tamaño; lo que no se puede mapear se rechaza con XMI_INVALID y detalle.
 */
@Component
public class XmiArchitectAdapter implements ArchitectAdapter {

    static final String XMI_NS = "http://www.omg.org/spec/XMI/20131001";
    static final String UML_NS = "http://www.omg.org/spec/UML/20161101";
    static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    static final String EXTENDER = "diagramas-platform";
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final String ID_PREFIX = "id_"; // los xmi:id deben ser NCName: no pueden empezar por dígito

    private final DiagramOperationApplier applier;

    public XmiArchitectAdapter(DiagramOperationApplier applier) {
        this.applier = applier;
    }

    // =================================================================== JSON → XMI

    @Override
    public String toXmi(JsonNode content, String modelName) {
        try {
            Document doc = newBuilder().newDocument();
            Element root = doc.createElementNS(XMI_NS, "xmi:XMI");
            root.setAttributeNS(XMLNS_NS, "xmlns:xmi", XMI_NS);
            root.setAttributeNS(XMLNS_NS, "xmlns:uml", UML_NS);
            root.setAttributeNS(XMI_NS, "xmi:version", "2.5.1");
            doc.appendChild(root);
            Element model = uml(doc, "uml:Model");
            model.setAttributeNS(XMI_NS, "xmi:id", "model_1");
            model.setAttribute("name", modelName == null || modelName.isBlank() ? "Model" : modelName);
            root.appendChild(model);

            Map<String, String> classIdByName = new HashMap<>();
            Map<String, JsonNode> classById = new LinkedHashMap<>();
            for (JsonNode c : content.path("classes")) {
                classIdByName.put(text(c, "name"), text(c, "id"));
                classById.put(text(c, "id"), c);
            }
            // Tipos que no son clases del diagrama (String, int, List<Cliente>…) → uml:PrimitiveType
            Map<String, String> primitiveIds = new LinkedHashMap<>();
            for (JsonNode c : content.path("classes")) {
                for (JsonNode a : c.path("attributes")) registerPrimitive(primitiveIds, classIdByName, text(a, "type"));
                for (JsonNode m : c.path("methods")) {
                    registerPrimitive(primitiveIds, classIdByName, text(m, "returnType"));
                    for (JsonNode p : m.path("parameters")) registerPrimitive(primitiveIds, classIdByName, text(p, "type"));
                }
            }
            primitiveIds.forEach((typeName, id) -> {
                Element prim = uml(doc, "packagedElement");
                prim.setAttributeNS(XMI_NS, "xmi:type", "uml:PrimitiveType");
                prim.setAttributeNS(XMI_NS, "xmi:id", id);
                prim.setAttribute("name", typeName);
                model.appendChild(prim);
            });

            for (JsonNode c : content.path("classes")) {
                model.appendChild(classElement(doc, c, content, classIdByName, primitiveIds));
            }
            for (JsonNode r : content.path("relationships")) {
                Element e = relationshipElement(doc, r);
                if (e != null) model.appendChild(e);
            }
            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new ApiException(ErrorCode.XMI_INVALID, "No se pudo convertir el diagrama a XMI.");
        }
    }

    private void registerPrimitive(Map<String, String> ids, Map<String, String> classIdByName, String type) {
        if (type == null || type.equals("void") || classIdByName.containsKey(type) || ids.containsKey(type)) return;
        ids.put(type, "prim_" + (ids.size() + 1));
    }

    private String typeRef(String type, Map<String, String> classIdByName, Map<String, String> primitiveIds) {
        if (type == null || type.equals("void")) return null;
        String classId = classIdByName.get(type);
        return classId != null ? ID_PREFIX + classId : primitiveIds.get(type);
    }

    private Element classElement(Document doc, JsonNode c, JsonNode content, Map<String, String> classIdByName,
                                 Map<String, String> primitiveIds) {
        String stereotype = text(c, "stereotype");
        String umlType = "interface".equals(stereotype) ? "uml:Interface" : "enum".equals(stereotype) ? "uml:Enumeration" : "uml:Class";
        Element el = uml(doc, "packagedElement");
        el.setAttributeNS(XMI_NS, "xmi:type", umlType);
        el.setAttributeNS(XMI_NS, "xmi:id", ID_PREFIX + text(c, "id"));
        el.setAttribute("name", text(c, "name"));
        el.setAttribute("visibility", text(c, "visibility") == null ? "public" : text(c, "visibility").toLowerCase(Locale.ROOT));
        if ("abstract".equals(stereotype)) el.setAttribute("isAbstract", "true");

        for (JsonNode a : c.path("attributes")) {
            Element attr = uml(doc, "ownedAttribute");
            attr.setAttributeNS(XMI_NS, "xmi:id", ID_PREFIX + text(a, "id"));
            attr.setAttribute("name", text(a, "name"));
            attr.setAttribute("visibility", text(a, "visibility").toLowerCase(Locale.ROOT));
            String ref = typeRef(text(a, "type"), classIdByName, primitiveIds);
            if (ref != null) attr.setAttribute("type", ref);
            el.appendChild(attr);
        }
        for (JsonNode m : c.path("methods")) {
            Element op = uml(doc, "ownedOperation");
            op.setAttributeNS(XMI_NS, "xmi:id", ID_PREFIX + text(m, "id"));
            op.setAttribute("name", text(m, "name"));
            op.setAttribute("visibility", text(m, "visibility").toLowerCase(Locale.ROOT));
            int i = 1;
            for (JsonNode p : m.path("parameters")) {
                Element param = uml(doc, "ownedParameter");
                param.setAttributeNS(XMI_NS, "xmi:id", ID_PREFIX + text(m, "id") + "_p" + i++);
                param.setAttribute("name", text(p, "name"));
                param.setAttribute("direction", "in");
                String ref = typeRef(text(p, "type"), classIdByName, primitiveIds);
                if (ref != null) param.setAttribute("type", ref);
                op.appendChild(param);
            }
            String ret = text(m, "returnType");
            if (ret != null && !ret.equals("void")) {
                Element rp = uml(doc, "ownedParameter");
                rp.setAttributeNS(XMI_NS, "xmi:id", ID_PREFIX + text(m, "id") + "_ret");
                rp.setAttribute("direction", "return");
                String ref = typeRef(ret, classIdByName, primitiveIds);
                if (ref != null) rp.setAttribute("type", ref);
                op.appendChild(rp);
            }
            el.appendChild(op);
        }
        // Herencia y realización viven dentro de la clase origen
        for (JsonNode r : content.path("relationships")) {
            if (!text(c, "id").equals(text(r, "sourceId"))) continue;
            String type = text(r, "type");
            if ("GENERALIZATION".equals(type)) {
                Element g = uml(doc, "generalization");
                g.setAttributeNS(XMI_NS, "xmi:type", "uml:Generalization");
                g.setAttributeNS(XMI_NS, "xmi:id", ID_PREFIX + text(r, "id"));
                g.setAttribute("general", ID_PREFIX + text(r, "targetId"));
                el.appendChild(g);
            } else if ("REALIZATION".equals(type)) {
                Element g = uml(doc, "interfaceRealization");
                g.setAttributeNS(XMI_NS, "xmi:type", "uml:InterfaceRealization");
                g.setAttributeNS(XMI_NS, "xmi:id", ID_PREFIX + text(r, "id"));
                g.setAttribute("contract", ID_PREFIX + text(r, "targetId"));
                g.setAttribute("supplier", ID_PREFIX + text(r, "targetId"));
                g.setAttribute("client", ID_PREFIX + text(c, "id"));
                g.setAttribute("implementingClassifier", ID_PREFIX + text(c, "id"));
                el.appendChild(g);
            }
        }
        // Layout en una extensión para no perder x,y en el ida y vuelta
        Element ext = doc.createElementNS(XMI_NS, "xmi:Extension");
        ext.setAttribute("extender", EXTENDER);
        Element layout = doc.createElement("layout");
        layout.setAttribute("x", String.valueOf(c.path("x").asLong(0)));
        layout.setAttribute("y", String.valueOf(c.path("y").asLong(0)));
        ext.appendChild(layout);
        el.appendChild(ext);
        return el;
    }

    private Element relationshipElement(Document doc, JsonNode r) {
        String type = text(r, "type");
        String id = ID_PREFIX + text(r, "id");
        if ("DEPENDENCY".equals(type)) {
            Element d = uml(doc, "packagedElement");
            d.setAttributeNS(XMI_NS, "xmi:type", "uml:Dependency");
            d.setAttributeNS(XMI_NS, "xmi:id", id);
            d.setAttribute("client", ID_PREFIX + text(r, "sourceId"));
            d.setAttribute("supplier", ID_PREFIX + text(r, "targetId"));
            return d;
        }
        if (!List.of("ASSOCIATION", "AGGREGATION", "COMPOSITION").contains(type)) {
            return null; // generalization / realization ya se emitieron dentro de la clase
        }
        Element a = uml(doc, "packagedElement");
        a.setAttributeNS(XMI_NS, "xmi:type", "uml:Association");
        a.setAttributeNS(XMI_NS, "xmi:id", id);
        if (text(r, "name") != null) a.setAttribute("name", text(r, "name"));
        Element m1 = uml(doc, "memberEnd");
        m1.setAttributeNS(XMI_NS, "xmi:idref", id + "_src");
        Element m2 = uml(doc, "memberEnd");
        m2.setAttributeNS(XMI_NS, "xmi:idref", id + "_tgt");
        a.appendChild(m1);
        a.appendChild(m2);
        a.appendChild(endElement(doc, id + "_src", text(r, "sourceRole"), text(r, "sourceId"), text(r, "sourceMultiplicity"), null));
        // El extremo con «aggregation» es el de la PARTE (destino); el todo es el origen
        String aggregation = "COMPOSITION".equals(type) ? "composite" : "AGGREGATION".equals(type) ? "shared" : null;
        a.appendChild(endElement(doc, id + "_tgt", text(r, "targetRole"), text(r, "targetId"), text(r, "targetMultiplicity"), aggregation));
        return a;
    }

    private Element endElement(Document doc, String id, String role, String classId, String multiplicity, String aggregation) {
        Element end = uml(doc, "ownedEnd");
        end.setAttributeNS(XMI_NS, "xmi:id", id);
        if (role != null) end.setAttribute("name", role);
        end.setAttribute("type", ID_PREFIX + classId);
        if (aggregation != null) end.setAttribute("aggregation", aggregation);
        if (multiplicity != null && !multiplicity.isBlank()) {
            String lower;
            String upper;
            int i = multiplicity.indexOf("..");
            if (i >= 0) {
                lower = multiplicity.substring(0, i);
                upper = multiplicity.substring(i + 2);
            } else if (multiplicity.equals("*")) {
                lower = "0";
                upper = "*";
            } else {
                lower = multiplicity;
                upper = multiplicity;
            }
            Element lv = uml(doc, "lowerValue");
            lv.setAttributeNS(XMI_NS, "xmi:type", "uml:LiteralInteger");
            lv.setAttributeNS(XMI_NS, "xmi:id", id + "_lower");
            lv.setAttribute("value", lower);
            Element uv = uml(doc, "upperValue");
            uv.setAttributeNS(XMI_NS, "xmi:type", "uml:LiteralUnlimitedNatural");
            uv.setAttributeNS(XMI_NS, "xmi:id", id + "_upper");
            uv.setAttribute("value", upper);
            end.appendChild(lv);
            end.appendChild(uv);
        }
        return end;
    }

    private static Element uml(Document doc, String name) {
        // Los elementos hijos de UML se emiten sin espacio de nombres propio (formato compacto de EA/XMI 2.5)
        return name.startsWith("uml:") ? doc.createElementNS(UML_NS, name) : doc.createElement(name);
    }

    private static String serialize(Document doc) throws TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        StringWriter out = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }

    // =================================================================== XMI → JSON

    @Override
    public XmiModel fromXmi(InputStream in) {
        Document doc = parseSecurely(in);
        try {
            Element model = findModel(doc.getDocumentElement());
            if (model == null) {
                throw xmiInvalid("No se encontró el modelo UML (uml:Model) en el archivo.");
            }
            List<Element> elements = new ArrayList<>();
            collectPackaged(model, elements);

            // Nombres de todos los tipos por id (clases, tipos primitivos, data types)
            Map<String, String> typeNames = new HashMap<>();
            for (Element e : elements) {
                String id = idOf(e);
                if (id != null && e.hasAttribute("name")) typeNames.put(id, e.getAttribute("name"));
            }

            ArrayNode classes = Json.array();
            ArrayNode relationships = Json.array();
            Map<String, String> classIds = new HashMap<>(); // id original (sin prefijo) → id
            int index = 0;
            List<Element> classElements = new ArrayList<>();
            for (Element e : elements) {
                String t = xmiType(e);
                if (t.equals("Class") || t.equals("Interface") || t.equals("Enumeration")) {
                    classElements.add(e);
                    String id = strip(idOf(e));
                    if (id == null) throw xmiInvalid("Una clase del XMI no tiene xmi:id.");
                    classIds.put(id, id);
                }
            }
            for (Element e : classElements) {
                String t = xmiType(e);
                String id = strip(idOf(e));
                ObjectNode cls = Json.object();
                cls.put("id", id);
                cls.put("name", e.getAttribute("name"));
                cls.put("visibility", visibility(e.getAttribute("visibility"), "PUBLIC"));
                if (t.equals("Interface")) cls.put("stereotype", "interface");
                else if (t.equals("Enumeration")) cls.put("stereotype", "enum");
                else if ("true".equalsIgnoreCase(e.getAttribute("isAbstract"))) cls.put("stereotype", "abstract");
                else cls.putNull("stereotype");
                Element layout = layoutOf(e);
                if (layout != null) {
                    cls.put("x", parseCoord(layout.getAttribute("x")));
                    cls.put("y", parseCoord(layout.getAttribute("y")));
                } else {
                    int[] pos = LayoutUtil.gridPosition(index);
                    cls.put("x", pos[0]);
                    cls.put("y", pos[1]);
                }
                index++;

                ArrayNode attributes = Json.array();
                for (Element a : children(e, "ownedAttribute")) {
                    if (a.hasAttribute("association")) continue; // extremo de asociación, no un atributo
                    ObjectNode attr = Json.object();
                    if (idOf(a) != null) attr.put("id", strip(idOf(a)));
                    attr.put("name", a.getAttribute("name"));
                    attr.put("type", typeOf(a, typeNames));
                    attr.put("visibility", visibility(a.getAttribute("visibility"), "PRIVATE"));
                    attributes.add(attr);
                }
                cls.set("attributes", attributes);

                ArrayNode methods = Json.array();
                for (Element o : children(e, "ownedOperation")) {
                    ObjectNode m = Json.object();
                    if (idOf(o) != null) m.put("id", strip(idOf(o)));
                    m.put("name", o.getAttribute("name"));
                    m.put("visibility", visibility(o.getAttribute("visibility"), "PUBLIC"));
                    String returnType = "void";
                    ArrayNode params = Json.array();
                    for (Element p : children(o, "ownedParameter")) {
                        String dir = p.hasAttribute("direction") ? p.getAttribute("direction") : "in";
                        if (dir.equals("return")) {
                            returnType = typeOf(p, typeNames);
                        } else {
                            ObjectNode param = Json.object();
                            param.put("name", p.getAttribute("name"));
                            param.put("type", typeOf(p, typeNames));
                            params.add(param);
                        }
                    }
                    m.put("returnType", returnType);
                    m.set("parameters", params);
                    methods.add(m);
                }
                cls.set("methods", methods);
                classes.add(cls);

                for (Element g : children(e, "generalization")) {
                    relationships.add(relationship(strip(idOf(g)), "GENERALIZATION", id, strip(refOf(g, "general")), classIds));
                }
                for (Element g : children(e, "interfaceRealization")) {
                    String contract = refOf(g, "contract") != null ? refOf(g, "contract") : refOf(g, "supplier");
                    relationships.add(relationship(strip(idOf(g)), "REALIZATION", id, strip(contract), classIds));
                }
            }

            // Asociaciones y dependencias
            for (Element e : elements) {
                String t = xmiType(e);
                if (t.equals("Dependency") || t.equals("Usage")) {
                    relationships.add(relationship(strip(idOf(e)), "DEPENDENCY",
                            strip(refOf(e, "client")), strip(refOf(e, "supplier")), classIds));
                } else if (t.equals("Association")) {
                    relationships.add(association(e, elements, classIds));
                }
            }

            ObjectNode content = Json.object();
            content.put("schemaVersion", 1);
            content.put("type", "CLASS");
            content.set("classes", classes);
            content.set("relationships", relationships);
            try {
                content = applier.normalizeContent(content);
            } catch (ApiException e) {
                throw xmiInvalid("El modelo XMI no es compatible: " + e.getMessage());
            }
            String name = model.hasAttribute("name") && !model.getAttribute("name").isBlank() ? model.getAttribute("name") : "Diagrama importado";
            return new XmiModel(name, content);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw xmiInvalid("No se pudo interpretar el contenido del XMI.");
        }
    }

    private ObjectNode relationship(String id, String type, String source, String target, Map<String, String> classIds) {
        if (source == null || target == null || !classIds.containsKey(source) || !classIds.containsKey(target)) {
            throw xmiInvalid("Una relación " + type + " referencia una clase que no existe en el modelo.");
        }
        ObjectNode r = Json.object();
        if (id != null) r.put("id", id);
        r.put("type", type);
        r.put("sourceId", source);
        r.put("targetId", target);
        return r;
    }

    private ObjectNode association(Element assoc, List<Element> all, Map<String, String> classIds) {
        List<End> ends = new ArrayList<>();
        for (Element oe : children(assoc, "ownedEnd")) {
            ends.add(endOf(oe));
        }
        String assocId = idOf(assoc);
        if (assocId != null) {
            for (Element cls : all) {
                for (Element a : children(cls, "ownedAttribute")) {
                    if (assocId.equals(a.getAttribute("association"))) ends.add(endOf(a));
                }
            }
        }
        if (ends.size() != 2) {
            throw xmiInvalid("La asociación '" + assoc.getAttribute("name") + "' debe tener exactamente 2 extremos (tiene " + ends.size() + ").");
        }
        End source = ends.get(0);
        End target = ends.get(1);
        String type = "ASSOCIATION";
        if (source.aggregation() != null && !"none".equals(source.aggregation())) {
            End tmp = source; source = target; target = tmp; // el extremo agregado es la parte (destino)
        }
        if ("composite".equals(target.aggregation())) type = "COMPOSITION";
        else if ("shared".equals(target.aggregation())) type = "AGGREGATION";

        ObjectNode r = relationship(strip(assocId), type, strip(source.type()), strip(target.type()), classIds);
        if (assoc.hasAttribute("name") && !assoc.getAttribute("name").isBlank()) r.put("name", assoc.getAttribute("name"));
        if (source.name() != null && !source.name().isBlank()) r.put("sourceRole", source.name());
        if (target.name() != null && !target.name().isBlank()) r.put("targetRole", target.name());
        String sm = multiplicity(source.lower(), source.upper());
        String tm = multiplicity(target.lower(), target.upper());
        if (sm != null) r.put("sourceMultiplicity", sm);
        if (tm != null) r.put("targetMultiplicity", tm);
        return r;
    }

    private static String multiplicity(String lower, String upper) {
        if (lower == null && upper == null) return null;
        String l = lower == null ? upper : lower;
        String u = upper == null ? lower : upper;
        return l.equals(u) ? l : l + ".." + u;
    }

    // ---- DOM helpers

    private static DocumentBuilder newBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder();
    }

    private Document parseSecurely(InputStream in) {
        try {
            byte[] data = in.readNBytes((int) MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw xmiInvalid("El archivo XMI excede el tamaño máximo permitido (5 MB).");
            }
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // sin DTD ⇒ sin XXE
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("Entidades externas no permitidas");
            });
            return b.parse(new java.io.ByteArrayInputStream(data));
        } catch (SAXException e) {
            throw xmiInvalid("El archivo no es un XMI/XML válido (los DTD y las entidades externas no están permitidos).");
        } catch (IOException | ParserConfigurationException e) {
            throw xmiInvalid("No se pudo leer el archivo XMI.");
        }
    }

    private static Element findModel(Element root) {
        if ("Model".equals(root.getLocalName())) return root;
        NodeList list = root.getElementsByTagNameNS("*", "Model");
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    /** Recorre packagedElement recursivamente, entrando a los uml:Package. */
    private static void collectPackaged(Element parent, List<Element> out) {
        for (Element e : children(parent, "packagedElement")) {
            if (xmiType(e).equals("Package")) {
                collectPackaged(e, out);
            } else {
                out.add(e);
            }
        }
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName() != null ? e.getLocalName() : e.getNodeName())) {
                result.add(e);
            }
        }
        return result;
    }

    /** Parte local de xmi:type sin prefijo (uml:Class → Class). */
    private static String xmiType(Element e) {
        String v = xmiAttr(e, "type");
        if (v == null) return "";
        int i = v.indexOf(':');
        return i >= 0 ? v.substring(i + 1) : v;
    }

    private static String idOf(Element e) {
        return xmiAttr(e, "id");
    }

    /** Atributo del espacio de nombres XMI (cualquier versión del URI), p. ej. xmi:id. */
    private static String xmiAttr(Element e, String local) {
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String ln = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
            String ns = a.getNamespaceURI();
            boolean xmi = (ns != null && ns.contains("XMI")) || "xmi".equals(a.getPrefix());
            if (xmi && ln.equals(local)) return a.getNodeValue();
        }
        return null;
    }

    /** Referencia por atributo (client="x") o por hijo (<client xmi:idref="x"/>). */
    private static String refOf(Element e, String name) {
        if (e.hasAttribute(name)) return e.getAttribute(name);
        for (Element c : children(e, name)) {
            String ref = xmiAttr(c, "idref");
            if (ref != null) return ref;
        }
        return null;
    }

    private static String strip(String id) {
        if (id == null) return null;
        return id.startsWith(ID_PREFIX) ? id.substring(ID_PREFIX.length()) : id;
    }

    private static Element layoutOf(Element classElement) {
        for (Element ext : children(classElement, "Extension")) {
            if (EXTENDER.equals(ext.getAttribute("extender"))) {
                List<Element> l = children(ext, "layout");
                if (!l.isEmpty()) return l.get(0);
            }
        }
        return null;
    }

    private static long parseCoord(String v) {
        try {
            return Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            throw xmiInvalid("Coordenada de layout no numérica: " + v);
        }
    }

    private static String visibility(String v, String def) {
        if (v == null || v.isBlank()) return def;
        String u = v.trim().toUpperCase(Locale.ROOT);
        return DiagramOperationApplier.VISIBILITIES.contains(u) ? u : def;
    }

    private String typeOf(Element e, Map<String, String> typeNames) {
        String raw = e.hasAttribute("type") ? e.getAttribute("type") : null;
        if (raw == null) {
            for (Element t : children(e, "type")) {
                String ref = xmiAttr(t, "idref");
                if (ref == null) ref = t.getAttribute("href");
                if (ref != null && !ref.isBlank()) {
                    int i = Math.max(ref.lastIndexOf('#'), ref.lastIndexOf('/'));
                    raw = i >= 0 ? ref.substring(i + 1) : ref;
                }
            }
        }
        if (raw == null || raw.isBlank()) return "String";
        String name = typeNames.getOrDefault(raw, typeNames.getOrDefault(strip(raw), raw));
        // Se devuelve el nombre de clase con su id de origen resuelto; los primitivos foráneos se canonizan
        return canonicalType(name);
    }

    private static final Map<String, String> TYPE_ALIASES = new HashMap<>();

    static {
        for (String t : DiagramOperationApplier.BASE_TYPES) TYPE_ALIASES.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
        TYPE_ALIASES.put("str", "String");
        TYPE_ALIASES.put("bool", "boolean");
        TYPE_ALIASES.put("date", "LocalDate");
        TYPE_ALIASES.put("datetime", "LocalDateTime");
        TYPE_ALIASES.put("decimal", "BigDecimal");
    }

    private static String canonicalType(String name) {
        if (DiagramOperationApplier.BASE_TYPES.contains(name) || name.matches("^(List|Set)<.+>$")) return name;
        return TYPE_ALIASES.getOrDefault(name.toLowerCase(Locale.ROOT), name);
    }

    private static ApiException xmiInvalid(String message) {
        return new ApiException(ErrorCode.XMI_INVALID, message);
    }

    private static End endOf(Element e) {
        String type = refOf(e, "type");
        String lower = valueOf(e, "lowerValue");
        String upper = valueOf(e, "upperValue");
        if ("-1".equals(upper)) upper = "*";
        String aggregation = e.hasAttribute("aggregation") ? e.getAttribute("aggregation") : null;
        return new End(type, e.hasAttribute("name") ? e.getAttribute("name") : null, lower, upper, aggregation);
    }

    private static String valueOf(Element e, String child) {
        for (Element c : children(e, child)) {
            if (c.hasAttribute("value")) return c.getAttribute("value");
        }
        return null;
    }

    private record End(String type, String name, String lower, String upper, String aggregation) {}
}
