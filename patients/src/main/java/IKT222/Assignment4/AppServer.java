package IKT222.Assignment4;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;

public class AppServer {
  public static void main(String[] args) throws Exception {
    Server server = new Server(8080);

    WebAppContext webapp = new WebAppContext();
    webapp.setContextPath("/");
    // Use absolute path so it works no matter where gradle starts from
    webapp.setResourceBase(new java.io.File("src/main/webapp").getAbsolutePath());
    webapp.setParentLoaderPriority(true);

    // Register our servlet (annotation mapping is also fine; this is explicit)
    webapp.addServlet(AppServlet.class, "/*");

    server.setHandler(webapp);
    server.start();
    server.join();
  }
}
