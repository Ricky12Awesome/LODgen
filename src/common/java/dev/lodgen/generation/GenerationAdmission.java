package dev.lodgen.generation;

/** Added to DH's builtin generator so its queue can respect the live window. */
public interface GenerationAdmission {
    boolean lodgen$isBusy();
}
