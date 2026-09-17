package org.example.radio.tui;

/** A decoded key press. */
public record Key(Key.Type type, char ch) {

    public enum Type {
        CHAR, UP, DOWN, LEFT, RIGHT, ENTER, ESCAPE, BACKSPACE, TAB, SHIFT_TAB,
        PAGE_UP, PAGE_DOWN, HOME, END, DELETE, NONE
    }

    public static final Key NONE = new Key(Type.NONE, '\0');

    public static Key of(Type type) {
        return new Key(type, '\0');
    }

    public static Key ofChar(char c) {
        return new Key(Type.CHAR, c);
    }

    public boolean is(char c) {
        return type == Type.CHAR && (ch == c || Character.toLowerCase(ch) == c);
    }

    public boolean isNone() {
        return type == Type.NONE;
    }
}
