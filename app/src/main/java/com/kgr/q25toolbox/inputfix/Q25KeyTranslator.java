package com.kgr.q25toolbox.inputfix;

import android.view.KeyEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Q25KeyTranslator {
    public enum Input {
        DIGIT_0,
        DIGIT_1,
        DIGIT_2,
        DIGIT_3,
        DIGIT_4,
        DIGIT_5,
        DIGIT_6,
        DIGIT_7,
        DIGIT_8,
        DIGIT_9,
        ENTER,
        DELETE,
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        DECIMAL,
        PERCENT,
        FACTORIAL,
        LEFT_PAREN,
        RIGHT_PAREN,
        SCIENTIFIC_TOGGLE,
    }

    private Q25KeyTranslator() {
    }

    public static Input toPinInput(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_1:
                return Input.DIGIT_1;
            case KeyEvent.KEYCODE_E:
            case KeyEvent.KEYCODE_2:
                return Input.DIGIT_2;
            case KeyEvent.KEYCODE_R:
            case KeyEvent.KEYCODE_3:
                return Input.DIGIT_3;
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_4:
                return Input.DIGIT_4;
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_5:
                return Input.DIGIT_5;
            case KeyEvent.KEYCODE_F:
            case KeyEvent.KEYCODE_6:
                return Input.DIGIT_6;
            case KeyEvent.KEYCODE_Z:
            case KeyEvent.KEYCODE_7:
                return Input.DIGIT_7;
            case KeyEvent.KEYCODE_X:
            case KeyEvent.KEYCODE_8:
                return Input.DIGIT_8;
            case KeyEvent.KEYCODE_C:
            case KeyEvent.KEYCODE_9:
                return Input.DIGIT_9;
            case KeyEvent.KEYCODE_0:
                return Input.DIGIT_0;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return Input.ENTER;
            case KeyEvent.KEYCODE_DEL:
                return Input.DELETE;
            default:
                return null;
        }
    }

    public static Input toCalculatorInput(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_1:
                return Input.DIGIT_1;
            case KeyEvent.KEYCODE_E:
            case KeyEvent.KEYCODE_2:
                return Input.DIGIT_2;
            case KeyEvent.KEYCODE_R:
            case KeyEvent.KEYCODE_3:
                return Input.DIGIT_3;
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_4:
                return Input.DIGIT_4;
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_5:
                return Input.DIGIT_5;
            case KeyEvent.KEYCODE_F:
            case KeyEvent.KEYCODE_6:
                return Input.DIGIT_6;
            case KeyEvent.KEYCODE_Z:
            case KeyEvent.KEYCODE_7:
                return Input.DIGIT_7;
            case KeyEvent.KEYCODE_X:
            case KeyEvent.KEYCODE_8:
                return Input.DIGIT_8;
            case KeyEvent.KEYCODE_C:
            case KeyEvent.KEYCODE_9:
                return Input.DIGIT_9;
            case KeyEvent.KEYCODE_0:
                return Input.DIGIT_0;
            case KeyEvent.KEYCODE_I:
                return Input.SUBTRACT;
            case KeyEvent.KEYCODE_O:
                return Input.ADD;
            case KeyEvent.KEYCODE_A:
                return Input.MULTIPLY;
            case KeyEvent.KEYCODE_B:
                return Input.FACTORIAL;
            case KeyEvent.KEYCODE_G:
                return Input.DIVIDE;
            case KeyEvent.KEYCODE_M:
                return Input.DECIMAL;
            case KeyEvent.KEYCODE_T:
                return Input.LEFT_PAREN;
            case KeyEvent.KEYCODE_Y:
                return Input.RIGHT_PAREN;
            case KeyEvent.KEYCODE_U:
                return Input.PERCENT;
            case KeyEvent.KEYCODE_SYM:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return Input.SCIENTIFIC_TOGGLE;
            case KeyEvent.KEYCODE_DEL:
                return Input.DELETE;
            default:
                return null;
        }
    }

    public static String systemUiPinButtonId(Input input) {
        if (input == null) return null;

        switch (input) {
            case DIGIT_0:
                return "com.android.systemui:id/key0";
            case DIGIT_1:
                return "com.android.systemui:id/key1";
            case DIGIT_2:
                return "com.android.systemui:id/key2";
            case DIGIT_3:
                return "com.android.systemui:id/key3";
            case DIGIT_4:
                return "com.android.systemui:id/key4";
            case DIGIT_5:
                return "com.android.systemui:id/key5";
            case DIGIT_6:
                return "com.android.systemui:id/key6";
            case DIGIT_7:
                return "com.android.systemui:id/key7";
            case DIGIT_8:
                return "com.android.systemui:id/key8";
            case DIGIT_9:
                return "com.android.systemui:id/key9";
            case ENTER:
                return "com.android.systemui:id/key_enter";
            case DELETE:
                return "com.android.systemui:id/delete_button";
            default:
                return null;
        }
    }

    public static List<String> systemUiPinButtonFallbackLabels(Input input) {
        if (input == null) return Collections.emptyList();

        switch (input) {
            case DIGIT_0:
                return Collections.singletonList("0");
            case DIGIT_1:
                return Collections.singletonList("1");
            case DIGIT_2:
                return Collections.singletonList("2");
            case DIGIT_3:
                return Collections.singletonList("3");
            case DIGIT_4:
                return Collections.singletonList("4");
            case DIGIT_5:
                return Collections.singletonList("5");
            case DIGIT_6:
                return Collections.singletonList("6");
            case DIGIT_7:
                return Collections.singletonList("7");
            case DIGIT_8:
                return Collections.singletonList("8");
            case DIGIT_9:
                return Collections.singletonList("9");
            default:
                return Collections.emptyList();
        }
    }

    public static String calculatorButtonId(Input input) {
        return calculatorButtonId("com.android.calculator2", input);
    }

    public static String calculatorDirectText(Input input) {
        if (input == null) return null;

        switch (input) {
            case FACTORIAL:
                return "!";
            case LEFT_PAREN:
                return "(";
            case RIGHT_PAREN:
                return ")";
            default:
                return null;
        }
    }

    public static String calculatorButtonId(String packageName, Input input) {
        if (packageName == null) return null;
        if (input == null) return null;

        String prefix = packageName + ":id/";
        switch (input) {
            case DIGIT_0:
                return prefix + "digit_0";
            case DIGIT_1:
                return prefix + "digit_1";
            case DIGIT_2:
                return prefix + "digit_2";
            case DIGIT_3:
                return prefix + "digit_3";
            case DIGIT_4:
                return prefix + "digit_4";
            case DIGIT_5:
                return prefix + "digit_5";
            case DIGIT_6:
                return prefix + "digit_6";
            case DIGIT_7:
                return prefix + "digit_7";
            case DIGIT_8:
                return prefix + "digit_8";
            case DIGIT_9:
                return prefix + "digit_9";
            case ADD:
                return prefix + "op_add";
            case SUBTRACT:
                return prefix + "op_sub";
            case MULTIPLY:
                return prefix + "op_mul";
            case DIVIDE:
                return prefix + "op_div";
            case DECIMAL:
                return prefix + "dec_point";
            case PERCENT:
                return prefix + "op_pct";
            case LEFT_PAREN:
            case RIGHT_PAREN:
                return prefix + "parens";
            case DELETE:
                return prefix + "del";
            case SCIENTIFIC_TOGGLE:
                return prefix + "collapse_expand";
            default:
                return null;
        }
    }

    public static List<String> calculatorButtonFallbackLabels(Input input) {
        if (input == null) return Collections.emptyList();

        switch (input) {
            case DIGIT_0:
                return Collections.singletonList("0");
            case DIGIT_1:
                return Collections.singletonList("1");
            case DIGIT_2:
                return Collections.singletonList("2");
            case DIGIT_3:
                return Collections.singletonList("3");
            case DIGIT_4:
                return Collections.singletonList("4");
            case DIGIT_5:
                return Collections.singletonList("5");
            case DIGIT_6:
                return Collections.singletonList("6");
            case DIGIT_7:
                return Collections.singletonList("7");
            case DIGIT_8:
                return Collections.singletonList("8");
            case DIGIT_9:
                return Collections.singletonList("9");
            case ADD:
                return Collections.singletonList("plus");
            case SUBTRACT:
                return Collections.singletonList("minus");
            case MULTIPLY:
                return Collections.singletonList("multiply");
            case DIVIDE:
                return Collections.singletonList("divide");
            case DECIMAL:
                return Collections.singletonList("point");
            case PERCENT:
                return Collections.singletonList("percent");
            case LEFT_PAREN:
            case RIGHT_PAREN:
                return Collections.singletonList("left or right parenthesis");
            case DELETE:
                return Collections.singletonList("Delete");
            case SCIENTIFIC_TOGGLE:
                return Arrays.asList("Show scientific buttons", "Hide scientific buttons");
            default:
                return Collections.emptyList();
        }
    }
}
