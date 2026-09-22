package com.diagramas.platform.design.collab;

import com.diagramas.platform.common.config.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP sobre WebSocket en /ws. Con {@code app.collab.distributed=true} los mensajes pasan por el
 * broker relay de RabbitMQ (plugin STOMP, puerto 61613) para que varias instancias converjan;
 * en modo local se usa el broker simple en memoria.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AppProperties props;
    private final StompAuthInterceptor authInterceptor;

    public WebSocketConfig(AppProperties props, StompAuthInterceptor authInterceptor) {
        this.props = props;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(props.corsAllowedOrigins().toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // Los clientes deben ver las operaciones en el mismo orden en que se aplicaron (orden por version).
        registry.setPreservePublishOrder(true);
        if (props.collab().distributed()) {
            var rabbit = props.rabbit();
            registry.enableStompBrokerRelay("/topic", "/queue")
                    .setRelayHost(rabbit.host())
                    .setRelayPort(rabbit.stompPort())
                    .setClientLogin(rabbit.user())
                    .setClientPasscode(rabbit.password())
                    .setSystemLogin(rabbit.user())
                    .setSystemPasscode(rabbit.password())
                    .setUserDestinationBroadcast("/topic/unresolved-user-dest")
                    .setUserRegistryBroadcast("/topic/simp-user-registry");
        } else {
            registry.enableSimpleBroker("/topic", "/queue");
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
