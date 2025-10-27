package IKT222.Assignment4;

import java.io.IOException;
import java.sql.*;
import java.util.*;

import javax.servlet.annotation.WebServlet;
import javax.servlet.ServletException;
import javax.servlet.http.*;

import freemarker.template.*;

@WebServlet(urlPatterns = { "/", "/login", "/search", "/logout" })
@SuppressWarnings("serial")
public class AppServlet extends HttpServlet {

  private static final String CONNECTION_URL = "jdbc:sqlite:db.sqlite3";

  private final Configuration fm = new Configuration(Configuration.VERSION_2_3_28);
  private Connection database;

  // ---- app startup ----------------------------------------------------------
  @Override
  public void init() throws ServletException {
    configureTemplateEngine();   // set FreeMarker template loader, etc.
    connectToDatabase();         // open SQLite and print absolute path

    // One-time migration: re-hash any plaintext passwords to bcrypt.
    // After you see [MIGRATE] logs once, comment this call out.
    try {
      bulkRehashLegacyPasswordsOnce();
    } catch (SQLException e) {
      throw new ServletException(e);
    }
  }

  // ---- templating -----------------------------------------------------------
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

  // ---- database -------------------------------------------------------------
  private void connectToDatabase() throws ServletException {
    try {
      database = DriverManager.getConnection(CONNECTION_URL);
      database.setAutoCommit(true); // ensure UPDATEs persist
      System.out.println("[DB] Connected to: " + new java.io.File("db.sqlite3").getAbsolutePath());
    } catch (SQLException error) {
      throw new ServletException(error.getMessage());
    }
  }

  // ---- helpers --------------------------------------------------------------
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

  // ---- HTTP handlers --------------------------------------------------------
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

  // ---- auth + data access ---------------------------------------------------
  // Login:
  // - prepared SELECT (prevents SQLi)
  // - if bcrypt hash present -> verify
  // - else if legacy plaintext matches -> upgrade to bcrypt and return true
  private boolean authenticated(String username, String password) throws SQLException {
    String sql = "SELECT id, password, password_hash FROM user WHERE username = ?";
    try (PreparedStatement ps = database.prepareStatement(sql)) {
      ps.setString(1, username);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return false;

        int id = rs.getInt("id");
        String legacyPlain = rs.getString("password");
        String storedHash  = rs.getString("password_hash");

        if (Passwords.looksHashed(storedHash)) {
          System.out.println("[AUTH] Using bcrypt verify for user: " + username);
          return Passwords.verify(password, storedHash);
        }

        String compare = (storedHash != null) ? storedHash : legacyPlain;
        if (compare != null && compare.equals(password)) {
          String newHash = Passwords.hash(password);
          try (PreparedStatement up = database.prepareStatement(
              "UPDATE user SET password_hash = ? WHERE id = ?")) {
            up.setString(1, newHash);
            up.setInt(2, id);
            int rows = up.executeUpdate();
            System.out.println("[AUTH] Upgraded plaintext -> bcrypt for user: " + username + " (rows=" + rows + ")");
          }
          return true;
        }
        System.out.println("[AUTH] Invalid password for user: " + username);
        return false;
      }
    }
  }

  // Patient search (prepared LIKE to avoid SQLi)
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

  // One-time startup migration: hash any row that still has plaintext.
  private void bulkRehashLegacyPasswordsOnce() throws SQLException {
    String select = "SELECT id, username, password, password_hash FROM user";
    try (Statement st = database.createStatement();
         ResultSet rs = st.executeQuery(select)) {
      while (rs.next()) {
        int id = rs.getInt("id");
        String username = rs.getString("username");
        String hash = rs.getString("password_hash");
        String plain = rs.getString("password");
        if (hash == null || hash.isEmpty() || !Passwords.looksHashed(hash)) {
          if (plain != null && !plain.isEmpty()) {
            String newHash = Passwords.hash(plain);
            try (PreparedStatement up = database.prepareStatement(
                "UPDATE user SET password_hash = ? WHERE id = ?")) {
              up.setString(1, newHash);
              up.setInt(2, id);
              up.executeUpdate();
              System.out.println("[MIGRATE] Rehashed user: " + username);
            }
          }
        }
      }
    }
  }
}
