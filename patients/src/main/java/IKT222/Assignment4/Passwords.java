package IKT222.Assignment4;

import org.mindrot.jbcrypt.BCrypt;

public class Passwords {
  private static final int WORK_FACTOR = 12;

  public static String hash(String plain) {
    return BCrypt.hashpw(plain, BCrypt.gensalt(WORK_FACTOR));
  }

  public static boolean verify(String plain, String hash) {
    if (hash == null) return false;
    try { return BCrypt.checkpw(plain, hash); }
    catch (IllegalArgumentException e) { return false; }
  }

  public static boolean looksHashed(String s) {
    return s != null && (s.startsWith("$2a$") || s.startsWith("$2b$") || s.startsWith("$2y$"));
  }
}
