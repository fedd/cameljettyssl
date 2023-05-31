/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */
package fedd;

import java.io.File;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jetty.JettyHttpComponent;
import org.apache.camel.component.jetty9.JettyHttpComponent9;
import org.apache.camel.http.common.HttpMessage;
import org.apache.camel.main.Main;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.support.jsse.KeyManagersParameters;
import org.apache.camel.support.jsse.KeyStoreParameters;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.eclipse.jetty.server.session.SessionHandler;

/**
 *
 * @author fedd
 */
public class CamelJettySsl {

    private static final String JETTY = "jetty";
    private static final String JETTYINSECURE = "jetty-insecure";

    public static void main(String[] args) throws Exception {

        // starting camel
        Main main = new Main();
        main.start();

        // getting context
        CamelContext camelContext = main.getCamelContext();

        // creating another jetty component for insecure access. So there are two jettys, one for http another for https
        // see https://stackoverflow.com/questions/67920367/create-http-and-https-endpoint-using-camel-in-the-same-server-with-jetty
        camelContext.addComponent(JETTYINSECURE, new JettyHttpComponent9());

        HttpSessionListener sessionListener = new HttpSessionListener() {
            @Override
            public void sessionCreated(HttpSessionEvent se) {
                System.out.println("-----------------------SESSIONSTARTED-----------------------");
            }

            @Override
            public void sessionDestroyed(HttpSessionEvent se) {
                System.out.println("-----------------------SESSIONDESTROYED-----------------------");
            }
        };

        // ssl magik. doesn't work, but used to work on old java and camel
        File keyStoreFile = new File("dela.p12");
        if (keyStoreFile.exists()) {
            String keystorePassword = "someSecretPassword";
            SSLContextParameters scp = new SSLContextParameters();
            KeyStoreParameters ksp = new KeyStoreParameters();
            ksp.setCamelContext(camelContext);
            ksp.setResource(keyStoreFile.getPath());
            ksp.setPassword(keystorePassword);
            ksp.setType("pkcs12");
            KeyManagersParameters kmp = new KeyManagersParameters();
            kmp.setKeyStore(ksp);
            kmp.setKeyPassword("someSecretPassword");
            scp.setKeyManagers(kmp);

            JettyHttpComponent jetty = camelContext.getComponent(JETTY, JettyHttpComponent.class);
            jetty.setSslContextParameters(scp);
        }

        // rest
        {
            final RestConfiguration restConfiguration = new RestConfiguration();
            restConfiguration.setHost("0.0.0.0");
            restConfiguration.setPort(8543);
            restConfiguration.setScheme("https");
            //restConfiguration.setComponent(restPort == _sslPort ? JETTY : JETTYINSECURE); //-- it doesn't expect jetty but a restlet impl
            restConfiguration.setBindingMode(RestConfiguration.RestBindingMode.auto);
            camelContext.setRestConfiguration(restConfiguration);
        }

        final SessionHandler sess = new SessionHandler();
        String sessionHandlerString = "jettySessionHandler";
        camelContext.getRegistry().bind(sessionHandlerString, sess);
        final SessionHandler sessHttps = new SessionHandler();
        String sessionHandlerHttpsString = "jettySessionHandlerHttps";
        camelContext.getRegistry().bind(sessionHandlerHttpsString, sessHttps);
        sess.addEventListener(sessionListener);
        sessHttps.addEventListener(sessionListener);

        RouteBuilder rb = new RouteBuilder(camelContext) {
            @Override
            public void configure() throws Exception {

                Processor dispatcher = new Processor() {

                    @Override
                    public void process(Exchange exchange) throws Exception {
                        HttpMessage http = exchange.getIn(HttpMessage.class);
                        HttpServletRequest request = http.getRequest();
                        String uripath = request.getRequestURI();
                        String method = request.getMethod();
                        request.isSecure();
                        HttpMessage msg = exchange.getIn(HttpMessage.class);
                        if (uripath.startsWith("/res/")) {
                            msg.setBody("resource: " + uripath.substring("/res/".length()) + " " + (request.isSecure() ? "secure" : "insecure"));
                        } else if ("POST".equals(method) || "PUT".equals(method)) {
                            msg.setBody("POSTED " + (request.isSecure() ? "secure" : "insecure"));
                        } else {
                            msg.setBody("HOORAY " + (request.isSecure() ? "secure" : "insecure"));
                        }
                    }
                };

                // initialize server with https and http
                from(JETTY + ":https://0.0.0.0:8543?matchOnUriPrefix=true&enableMultipartFilter=true&handlers=jettySessionHandlerHttps")
                        .process(dispatcher);

                from(JETTYINSECURE + ":http://0.0.0.0:8585?matchOnUriPrefix=true&enableMultipartFilter=true&handlers=jettySessionHandler")
                        .process(dispatcher);

            }
        };

        try {
            camelContext.addRoutes(rb);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}
