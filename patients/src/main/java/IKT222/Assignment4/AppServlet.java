package IKT222.Assignment4;

import java.io.IOException;
import java.sql.*;
import java.util.*;

import javax.servlet.annotation.WebServlet;
import javax.servlet.ServletException;
import javax.servlet.http.*;

import freemarker.template.*;

@WebServlet(urlPatterns = { "/", "/login", "/search", "/logout" }, loadOnStartup = 1)
@SuppressWarnings("serial")
public class AppServlet extends HttpServlet {

  private final Configuration fm = new Configuration(Configuration.VERSION_2_3_28);
  private Connection database;

  @Override
  public void init() throws ServletException {
    configureTemplateEngine();
    connectToDatabase(); // same absolute-root db.sqlite3 as before
  }

  private void configureTemplateEngine() throws ServletException {
    try {
      fm.setServletContextForTemplateLoading(getServletContext(), "/WEB-INF/templates");
      fm.setDefaultEncoding("UTF-8");
      // Keep debug handler during development so you see useful template errors:
      fm.setTemplateExceptionHandler(TemplateExceptionHandler.HTML_DEBUG_HANDLER);
      fm.setLogTemplateExceptions(false);
      fm.setWrapUncheckedExceptions(true);
      fm.setTemplateUpdateDelayMilliseconds(0); // hot reload while dev
    } catch (Exception error) {
      throw new ServletException(error.getMessage());
    }
  }

  private void connectToDatabase() throws ServletException {
    try {
      // Use the exact same DB file that was working for you:
      String url = "jdbc:sqlite:" + new java.io.File("db.sqlite3").getAbsolutePath();
      database = DriverManager.getConnection(url);
      database.setAutoCommit(true);
    } catch (SQLException error) {
      throw new ServletException(error);
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
          try { req.setCharacterEncoding("UTF-8"); } catch (Exception ignore) {}
          String u = optTrim(req.getParameter("username"));
          String p = optTrim(req.getParameter("password"));

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

  private static String optTrim(String s) {
    return (s == null) ? null : s.trim();
  }

  // Auth: look up the user's bcrypt hash and verify it.
  private boolean authenticated(String username, String password) throws SQLException {
    if (username == null || password == null) return false;

    String sql = "SELECT password_hash FROM user WHERE username = ?";
    try (PreparedStatement ps = database.prepareStatement(sql)) {
      ps.setString(1, username);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return false;      // no such user
        String hash = rs.getString(1);     // bcrypt hash
        return hash != null && Passwords.verify(password, hash);
      }
    }
  }

  private java.util.List<Record> searchResults(String surname) throws SQLException {
    java.util.List<Record> list = new java.util.ArrayList<>();
    String sql = "SELECT * FROM patient WHERE surname LIKE ?";
    try (PreparedStatement ps = database.prepareStatement(sql)) {
      ps.setString(1, (surname == null ? "" : surname) + "%");
      try (ResultSet rs = ps.executeQuery()) {
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
    }
    return list;
  }
}
