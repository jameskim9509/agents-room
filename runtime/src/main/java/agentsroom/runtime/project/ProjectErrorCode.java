package agentsroom.runtime.project;

/** Why {@code openProject} refused a path, in the order the checks run (D17). */
public enum ProjectErrorCode {
    /** The path is not absolute (relative, drive-relative or empty). */
    PATH_NOT_ABSOLUTE,
    /** Nothing exists at the path, or the string is not a valid path on this OS. */
    PATH_NOT_FOUND,
    /** The path exists but is not a directory. */
    PATH_NOT_DIRECTORY,
    /** The directory exists but its entries cannot be listed. */
    PATH_NOT_READABLE
}
