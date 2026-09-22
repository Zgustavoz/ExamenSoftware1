"""Contratos de entrada/salida (10.2). La salida del LLM se valida con estos modelos antes de responder."""

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

OpType = Literal[
    "ADD_CLASS", "UPDATE_CLASS", "MOVE_CLASS", "REMOVE_CLASS",
    "ADD_ATTRIBUTE", "UPDATE_ATTRIBUTE", "REMOVE_ATTRIBUTE",
    "ADD_METHOD", "UPDATE_METHOD", "REMOVE_METHOD",
    "ADD_RELATIONSHIP", "UPDATE_RELATIONSHIP", "REMOVE_RELATIONSHIP",
]


class HistoryItem(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class InterpretRequest(BaseModel):
    instruction: str = Field(min_length=1, max_length=4000)
    inputType: Literal["TEXTO", "VOZ"] = "TEXTO"
    contentJson: dict[str, Any]
    history: list[HistoryItem] = Field(default_factory=list, max_length=40)


class Operation(BaseModel):
    """Operación del vocabulario 7.3. Las clases se referencian POR NOMBRE (el backend las resuelve a id)."""

    model_config = ConfigDict(populate_by_name=True)

    op: OpType
    class_: dict[str, Any] | None = Field(default=None, alias="class")
    className: str | None = None
    attribute: dict[str, Any] | None = None
    attributeName: str | None = None
    method: dict[str, Any] | None = None
    methodName: str | None = None
    relationship: dict[str, Any] | None = None
    source: str | None = None
    target: str | None = None
    changes: dict[str, Any] | None = None
    x: float | None = None
    y: float | None = None

    @model_validator(mode="after")
    def _required_fields(self) -> "Operation":
        def need(*names: str) -> None:
            missing = [n for n in names if not getattr(self, n)]
            if missing:
                raise ValueError(f"{self.op} requiere: {', '.join(missing)}")

        match self.op:
            case "ADD_CLASS":
                need("class_")
                if not str(self.class_.get("name", "")).strip():
                    raise ValueError("ADD_CLASS requiere class.name")
            case "UPDATE_CLASS":
                need("className", "changes")
            case "MOVE_CLASS":
                need("className")
                if self.x is None or self.y is None:
                    raise ValueError("MOVE_CLASS requiere x e y")
            case "REMOVE_CLASS":
                need("className")
            case "ADD_ATTRIBUTE":
                need("className", "attribute")
                if not self.attribute.get("name") or not self.attribute.get("type"):
                    raise ValueError("ADD_ATTRIBUTE requiere attribute.name y attribute.type")
            case "UPDATE_ATTRIBUTE":
                need("className", "attributeName", "changes")
            case "REMOVE_ATTRIBUTE":
                need("className", "attributeName")
            case "ADD_METHOD":
                need("className", "method")
                if not self.method.get("name"):
                    raise ValueError("ADD_METHOD requiere method.name")
            case "UPDATE_METHOD":
                need("className", "methodName", "changes")
            case "REMOVE_METHOD":
                need("className", "methodName")
            case "ADD_RELATIONSHIP":
                need("relationship")
                for key in ("type", "source", "target"):
                    if not self.relationship.get(key):
                        raise ValueError(f"ADD_RELATIONSHIP requiere relationship.{key}")
            case "UPDATE_RELATIONSHIP":
                need("source", "target", "changes")
            case "REMOVE_RELATIONSHIP":
                need("source", "target")
        return self


class InterpretResponse(BaseModel):
    explanation: str = ""
    operations: list[Operation]


class SequenceRequest(BaseModel):
    contentJson: dict[str, Any]


class Lifeline(BaseModel):
    name: str = Field(min_length=1)
    className: str | None = None


class SequenceMessage(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    order: int
    from_: str = Field(alias="from", min_length=1)
    to: str = Field(min_length=1)
    name: str = Field(min_length=1)
    kind: Literal["SYNC", "ASYNC", "RETURN", "SELF"] = "SYNC"


class SequenceResponse(BaseModel):
    lifelines: list[Lifeline] = Field(min_length=1)
    messages: list[SequenceMessage] = Field(min_length=1)

    @model_validator(mode="after")
    def _messages_reference_lifelines(self) -> "SequenceResponse":
        names = {l.name.strip().lower() for l in self.lifelines}
        for m in self.messages:
            if m.from_.strip().lower() not in names or m.to.strip().lower() not in names:
                raise ValueError(f"El mensaje '{m.name}' referencia una línea de vida inexistente")
        return self


# ---------------------------------------------------------------- CU-M1: orden hablada → llamada HTTP


class EntityField(BaseModel):
    name: str
    type: str


class EntityInfo(BaseModel):
    """Una entidad del backend generado, tal como la expone su API REST."""

    name: str
    path: str
    fields: list[EntityField] = Field(default_factory=list)


class CommandRequest(BaseModel):
    instruction: str = Field(min_length=1, max_length=2000)
    entities: list[EntityInfo] = Field(min_length=1, max_length=50)


class CommandResponse(BaseModel):
    """Llamada que hay que hacer contra el backend generado para cumplir la orden."""

    explanation: str
    method: Literal["GET", "POST", "PUT", "DELETE"]
    path: str = Field(min_length=1, max_length=200)
    body: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def _path_is_relative(self) -> "CommandResponse":
        # El backend solo ejecuta rutas de su propia API: nada de URLs absolutas ni de subir de directorio.
        if not self.path.startswith("/") or ".." in self.path or "//" in self.path[1:]:
            raise ValueError("la ruta debe ser relativa y empezar por /")
        return self
