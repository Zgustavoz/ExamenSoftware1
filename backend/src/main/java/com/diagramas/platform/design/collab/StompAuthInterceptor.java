package com.diagramas.platform.design.collab;

import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.security.JwtService;
import com.diagramas.platform.design.repository.DiagramRepository;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Autentica el CONNECT de STOMP con el JWT (cabecera Authorization) y, al suscribirse a un diagrama,
 * exige que pertenezca a la empresa del token (aislamiento multi-tenant).
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Pattern DIAGRAM_TOPIC = Pattern.compile("^/topic/diagram\\.([0-9a-fA-F-]{36})$");

    private final JwtService jwt;
    private final DiagramRepository diagrams;

    public StompAuthInterceptor(JwtService jwt, DiagramRepository diagrams) {
        this.jwt = jwt;
        this.diagrams = diagrams;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new MessagingException("Token requerido");
            }
            try {
                AuthPrincipal p = jwt.parse(header.substring(7).trim());
                accessor.setUser(new UsernamePasswordAuthenticationToken(
                        p, null, p.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
            } catch (JwtException | IllegalArgumentException e) {
                throw new MessagingException("Token inválido");
            }
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth)
                    || !(auth.getPrincipal() instanceof AuthPrincipal p)) {
                throw new MessagingException("No autenticado");
            }
            String destination = accessor.getDestination();
            Matcher m = destination == null ? null : DIAGRAM_TOPIC.matcher(destination);
            if (m != null && m.matches()
                    && !diagrams.existsByIdAndCompanyId(UUID.fromString(m.group(1)), p.companyId())) {
                throw new MessagingException("Diagrama no encontrado");
            }
        }
        return message;
    }
}
