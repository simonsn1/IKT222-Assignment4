package IKT222.Assignment4;

import java.io.IOException;
import java.sql.*;
import java.util.*;

import javax.servlet.annotation.WebServlet;
import javax.servlet.ServletException;
import javax.servlet.http.*;

import freemarker.template.*;

@WebServlet(urlPatterns = {"/", "/login", "/search", "/logout"})
@SuppressWarnings("serial")
public class AppServlet extends HttpServlet {

  // simple setup – same DB as starter
  private static final String CONNECTION_URL = "jdbc:sqlite:db.sqlite3";
  private static final String AUTH_QUERY   = "select * from user where username='%s' and password='%s'";
  private static final String SEARCH_QUERY = "select * from patient where surname like '%s'";

  private final Configuration fm = new Configuration(Configuration.VERSION_2_3_28);
  private Connection database;

  @Override
  public void init() throws ServletException {
    configureTemplateEngine();
    connectToDatabase();
  }

  // moved templates under /WEB-INF/templates, load via servlet context
  private void configureTemplateEngine() throws ServletException {
    try {
      fm.setServletContextForTemplateLoading(getServletContext(), "/WEB-INF/templates");
      fm.setDefaultEncoding("UTF-8");
      fm.setTemplateExceptionHandler(TemplateExceptionHandler.HTML_DEBUG_HANDLER);
      fm.setLogTemplateExceptions(false);
      fm.setWrapUncheckedExceptions(true);
    } catch (Exception error) {
      throw new ServletException(error.getMessage());
    }
  }

  private void connectToDatabase() throws ServletException {
    try {
      database = DriverManager.getConnection(CONNECTION_URL);
    } catch (SQLException error) {
      throw new ServletException(error.getMessage());
    }
  }

  private String path(HttpServletRequest req) {
    String p = req.getServletPath();
    if (p == null || p.isEmpty()) p = req.getPathInfo();
    return (p == null || p.isEmpty()) ? "/login" : p;
  }

  private boolean loggedIn(HttpServletRequest req) {
    HttpSession s = req.getSession(false);
    return s != null && s.getAttribute("user") != null;
  }

  private void render(String tpl, Map<String,Object> model, HttpServletResponse res)
      throws IOException, TemplateException {
    res.setContentType("text/html; charset=UTF-8");
    Template t = fm.getTemplate(tpl);
    t.process(model, res.getWriter());
  }

  private String ctx(HttpServletRequest req) {
    String c = req.getContextPath();
    return c == null ? "" : c;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {
    try {
      switch (path(req)) {
        case "/login": {
          Map<String,Object> m = new HashMap<>();
          m.put("expired",  "1".equals(req.getParameter("expired")));
          m.put("loggedOut","1".equals(req.getParameter("loggedOut")));
          m.put("invalid",  "1".equals(req.getParameter("invalid")));
          render("login.html", m, res);
          break;
        }
        case "/search": {
          if (!loggedIn(req)) { res.sendRedirect(ctx(req) + "/login?expired=1"); break; }
          Map<String,Object> m = new HashMap<>();
          m.put("username", req.getSession(false).getAttribute("user"));
          render("search.html", m, res);
          break;
        }
        case "/logout": {
          HttpSession s = req.getSession(false);
          if (s != null) s.invalidate();
          res.sendRedirect(ctx(req) + "/login?loggedOut=1");
          break;
        }
        default: res.sendRedirect(ctx(req) + "/login");
      }
    } catch (TemplateException e) {
      res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {
    try {
      switch (path(req)) {
        case "/login": {
          // create session on successful login
          String u = req.getParameter("username");
          String p = req.getParameter("password");
          if (authenticated(u, p)) {
            HttpSession s = req.getSession(true);
            s.setAttribute("user", u);
            s.setMaxInactiveInterval(15 * 60);
            try { req.changeSessionId(); } catch (Throwable ignore) {}
            res.sendRedirect(ctx(req) + "/search");
          } else {
            res.sendRedirect(ctx(req) + "/login?invalid=1");
          }
          break;
        }
        case "/search": {
          // only allow search if logged in
          if (!loggedIn(req)) { res.sendRedirect(ctx(req) + "/login?expired=1"); break; }
          String surname = req.getParameter("surname");
          Map<String,Object> m = new HashMap<>();
          m.put("records", searchResults(surname));
          render("details.html", m, res);
          break;
        }
        default: res.sendRedirect(ctx(req) + "/login");
      }
    } catch (TemplateException e) {
      res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    } catch (Exception e) {
      res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
  }

  private boolean authenticated(String username, String password) throws SQLException {
    String q = String.format(AUTH_QUERY, username, password);
    try (Statement st = database.createStatement()) {
      ResultSet rs = st.executeQuery(q);
      return rs.next();
    }
  }

  private java.util.List<Record> searchResults(String surname) throws SQLException {
    java.util.List<Record> list = new java.util.ArrayList<>();
    String q = String.format(SEARCH_QUERY, surname);
    try (Statement st = database.createStatement()) {
      ResultSet rs = st.executeQuery(q);
      while (rs.next()) {
        Record r = new Record();
        r.setSurname(rs.getString(2));
        r.setForename(rs.getString(3));
        r.setAddress(rs.getString(4));
        r.setDateOfBirth(rs.getString(5));
        r.setDoctorId(rs.getString(6));
        r.setDiagnosis(rs.getString(7));
        list.add(r);
      }
    }
    return list;
  }
}
