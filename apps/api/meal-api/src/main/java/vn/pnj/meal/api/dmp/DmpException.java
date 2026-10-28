package vn.pnj.meal.api.dmp;

/** A DMP call failed; callers write nothing (plan section 5: data only after a full success). */
public class DmpException extends RuntimeException {
  public enum Kind {
    API_ERROR,
    AUTH,
    TIMEOUT,
    NOT_IMPLEMENTED
  }

  private final Kind kind;

  public DmpException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public DmpException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
