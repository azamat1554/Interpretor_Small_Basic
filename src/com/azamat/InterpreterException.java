package com.azamat;

/**
 * Класс исключений для ошибок интерпретатора
 */
public class InterpreterException extends Exception {
    String errStr; //описание ошибки

    public InterpreterException(String str) {
        errStr = str;
    }

    public String toString() {
        return  errStr;
    }
}
