/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */
package fedd;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import org.apache.camel.CamelConfiguration;
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

        main.configure().addConfiguration(new CamelConfiguration() {
            @Override
            public void configure(CamelContext camelContext) throws Exception {
            }

        });

        main.configure().addRoutesBuilder(new RouteBuilder() {

            @Override
            public void configure() throws Exception {

                // creating another jetty component for insecure access. So there are two jettys, one for http another for https
                // see https://stackoverflow.com/questions/67920367/create-http-and-https-endpoint-using-camel-in-the-same-server-with-jetty
                getCamelContext().addComponent(JETTYINSECURE, new JettyHttpComponent9());

                // ssl magik. doesn't work, but used to work on old java and camel
                File keyStoreFile = new File("dela.p12");
                if (keyStoreFile.exists()) {
                    String keystorePassword = "someSecretPassword";
                    SSLContextParameters scp = new SSLContextParameters();
                    KeyStoreParameters ksp = new KeyStoreParameters();
                    var ks = KeyStore.getInstance("JKS");
                    try (var stream = Files.newInputStream(Path.of(keyStoreFile.getPath()))) {
                        ks.load(stream, keystorePassword.toCharArray());
                    }
                    ksp.setKeyStore(ks);
                    KeyManagersParameters kmp = new KeyManagersParameters();
                    kmp.setKeyStore(ksp);
                    kmp.setKeyPassword("someSecretPassword");
                    scp.setKeyManagers(kmp);

                    // add the SSL only to the secure jetty
                    JettyHttpComponent jetty = getCamelContext().getComponent(JETTY, JettyHttpComponent.class);
                    jetty.setSslContextParameters(scp);
                }

                // rest. This will use the secure jetty (based on the port number)
                {
                    final RestConfiguration restConfiguration = new RestConfiguration();
                    restConfiguration.setHost("0.0.0.0");
                    restConfiguration.setPort(8543);
                    restConfiguration.setScheme("https");
                    //restConfiguration.setComponent(restPort == _sslPort ? JETTY : JETTYINSECURE); //-- it doesn't expect jetty but a restlet impl
                    restConfiguration.setBindingMode(RestConfiguration.RestBindingMode.auto);
                    getCamelContext().setRestConfiguration(restConfiguration);
                }

                // Jetty style session handlers. They can't be reused in both components of jetty, so there are two of them
                // this one for the insecure jetty
                final SessionHandler sess = new SessionHandler();
                String sessionHandlerString = "jettySessionHandler";
                getCamelContext().getRegistry().bind(sessionHandlerString, sess); // supposed to make it available to the "from" uri
                // thsi one for the https jetty
                final SessionHandler sessHttps = new SessionHandler();
                String sessionHandlerHttpsString = "jettySessionHandlerHttps";
                getCamelContext().getRegistry().bind(sessionHandlerHttpsString, sessHttps);

                // a standard session listener that will be invoked by the jetty handlers (see below)
                // TODO: doesn't work
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
                // this object can be used in both copies of jetty component
                sess.addEventListener(sessionListener);
                sessHttps.addEventListener(sessionListener);

                // a simple home grown dispatcher for the catch-all uri path
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

                // initialize two jettys with https and http
                // session handler doesn't get invoked (neither with nor without the #hash)
                from(JETTY + ":https://0.0.0.0:8543?matchOnUriPrefix=true&enableMultipartFilter=true&handlers=#jettySessionHandlerHttps")
                        .process(dispatcher);

                from(JETTYINSECURE + ":http://0.0.0.0:8585?matchOnUriPrefix=true&enableMultipartFilter=true&handlers=#jettySessionHandler")
                        .process(dispatcher);

            }
        });

        main.run();

    }
}
