package com.azamat;

import com.sun.org.apache.regexp.internal.RE;

import java.io.*;
import java.util.*;
/**
 * Класс интерпретатора языка SmallBasic
 */
public class SBasic {
    final int PROG_SIZE = 10_000; //максимальный размер программы

    //типы лексем
    final int NONE = 0;
    final int DELIMITER = 1;
    final int VARIABLE = 2;
    final int NUBMER = 3;
    final int COMMAND = 4;
    final int QUTEDSTR = 5;

    //типы ошибок
    final int SYNTAX = 0;
    final int UNBALPARENS = 1;
    final int NOEXP = 2;
    final int DIVBYZERO = 3;
    final int EQUALEXPECTED = 4;
    final int NOTVAR = 5;
    final int LABELTABLEFULL = 6;
    final int DUPLABEL = 7;
    final int UNDEFLABEL = 8;
    final int THENECPECTED = 9;
    final int TOEXPECTED = 10;
    final int NEXTWITHOUTFOR = 11;
    final int RETURNWITHOUTGOSUB = 12;
    final int MISSINGQOUTE = 13;
    final int FILENOUFOUND = 14;
    final int FILEIOERROR = 15;
    final int INPUTIOERROR = 16;

    //внутреннее представление ключевых слов SmallBasic
    final int UNKNCOM = 0;
    final int PRINT = 1;
    final int INPUT = 2;
    final int IF = 3;
    final int THEN = 4;
    final int FOR = 5;
    final int NEXT = 6;
    final int TO = 7;
    final int GOTO = 8;
    final int GOSUB = 9;
    final int RETURN = 10;
    final int END = 11;
    final int EOL = 12;

    //лексема конца программы, EndOfProgramm
    final String EOP = "\0";

    //коды для двойных операторов, таких как <=
    final char LE = 1;
    final char GE = 2;
    final char NE = 3;

    //массив для переменных
    private double vars[];

    //В этом классе связываются ключевые слова с их лексемами
    class Keyword {
        String keyword; //строка
        int keywordTok; //внутреннее предствление

        Keyword(String str, int t) {
            keyword = str;
            keywordTok = t;
        }
    }

    /* Таблица ключевых слов с их внтренним предствлением.
     * Все слова должны вводиться в нижнем регистре.
     */
    Keyword kwTable[] = {
            new Keyword("print", PRINT),
            new Keyword("input", INPUT),
            new Keyword("if", IF),
            new Keyword("then", THEN),
            new Keyword("goto", GOTO),
            new Keyword("for", FOR),
            new Keyword("next", NEXT),
            new Keyword("to", TO),
            new Keyword("gosub", GOSUB),
            new Keyword("return", RETURN),
            new Keyword("end", END)
    };

    private char[] prog; //ссылка на массив с программой
    private int progIdx; //текущий индекс в программе
    private String token; //сохраняет тукущую лексему
    private int tokType; //сохраняет тип лексемы
    private int kwToken; //внутреннее представление ключевого слова

    //Подержка цикла FOR
    class ForInfo {
        int var; //счетчик
        double target; //начение
        int loc; //индекс в исходном коде для цикла
    }

    //стек для циклов FOR
    private  Stack fStack;

    //определяем таблицу вхождения меток
    class Label {
        String name; //метка
        int loc; //индекс положения метки в исходном файле

        public Label(String str, int i) {
            name = str;
            loc = i;
        }
    }

    //распределение меток
    private TreeMap labelTable;

    //стек для gosubs
    private Stack gStack;

    //операторы отношения
    char rops[] = {
            GE, NE, LE, '<', '>', '=', 0
    };

    /* Создать строку, содержащую операторы отношения,
     * для того, чтобы сделать их проверку болеее удобной. */
    String relops = new String(rops);

    //конструкторы для Small Basic
    public SBasic(String progName) throws InterpreterException {
        char tempbuf[] = new char[PROG_SIZE];
        int size;

        //загрузить программу для выполнения
        size = loadProgram(tempbuf, progName);
        if (size != -1) {
            //создать соответствующий макссив для хранения программы
            prog = new char[size];

            //копировать программу в массив
            System.arraycopy(tempbuf, 0, prog, 0, size);
        }
    }

    //загрузить программу
    private int loadProgram(char[] p, String fname) throws InterpreterException {
        int size = 0;
        try {
            FileReader fr = new FileReader(fname);
            BufferedReader br = new BufferedReader(fr);
            size = br.read(p, 0, PROG_SIZE);
            fr.close();
        } catch (FileNotFoundException e) {
            handleErr(FILENOUFOUND);
        } catch (IOException e) {
            handleErr(FILEIOERROR);
        }

        //если файл оканчивается маркером EOF, сделать возврат
        if (p[size - 1] == (char) 26) size--;
        return size; //возвратить размер программы
    }

    //выполнить программу
    public void run() throws InterpreterException {
        //инициализировать для запуска новой программы
        vars = new double[26];
        fStack = new Stack();
        labelTable = new TreeMap();
        gStack = new Stack();
        progIdx = 0;
        scanLabels(); //найти метки в программе
        sbInterp(); //выполнить
    }

    //точка входа для интерпретатора SmallBasic
    private void sbInterp() throws InterpreterException {
        //Основной цикл интерпретатора
        do {
            getToken();
            //проверка на наличие оператора присваивания
            if (tokType == VARIABLE) {
                putBack(); //возвратить переменную во входной поток
                assignment(); //обработать оператор присваивания
            } else //если ключевое слово
                switch (kwToken) {
                    case PRINT:
                        print();
                        break;
                    case GOTO:
                        execGoto();
                        break;
                    case IF:
                        execIf();
                        break;
                    case FOR:
                        execFor();
                        break;
                    case NEXT:
                        next();
                        break;
                    case INPUT:
                        input();
                        break;
                    case GOSUB:
                        gosub();
                        break;
                    case RETURN:
                        greturn();
                        break;
                    case END:
                        return;
                }
        } while (!token.equals(EOP));

    }

    //найти все метки
    private void scanLabels() throws InterpreterException {
        int i;
        Object result;

        //посмотреть, является ли первая лексема в файле меткой
        getToken();
        if (tokType == NUBMER)
            labelTable.put(token, new Integer(progIdx));
        findEOL();
        do {
            getToken();
            if (tokType == NUBMER) { //должен быть номер строки
                result = labelTable.put(token, new Integer(progIdx));

                if (result != null) handleErr(DUPLABEL);
            }
            //найти следующую строку
            if (kwToken != EOL) findEOL();
        } while (!token.equals(EOP));
        progIdx = 0; //переустановить индекс в начало программы
    }

    //найти начало следующей строки
    private void findEOL() {
        while (progIdx < prog.length && prog[progIdx] != '\n')
            progIdx++;
    }

    //присвоить переменной значение
    private void assignment() throws InterpreterException {
        int var;
        double value;
        char vname;

        //получить имя переменной
        getToken();
        vname = token.charAt(0);

        if (!Character.isLetter(vname)) {
            handleErr(NOTVAR);
            return;
        }

        //преобразовать индекс по таблице переменных
        var = (int) Character.toUpperCase(vname) - 'A';

        //получить знак равенства
        getToken();
        if (!token.equals("=")) {
            handleErr(EQUALEXPECTED);
            return;
        }

        //получить значение для присваивания
        value = evaluate();

        //присвоить значение
        vars[var] = value;
    }

    //выполнить просную версию утверждения PRINT
    private void print() throws InterpreterException {
        double result;
        int len = 0, spaces;
        String lastDelim = "";

        do {
            getToken(); //получить следующий элемент
            if (kwToken == EOL || token.equals(EOP)) break;

            if (tokType == QUTEDSTR) { //строка
                System.out.print(token);
                len += token.length();
                getToken();
            } else {                    //выражение
                putBack();
                result = evaluate();
                getToken();
                System.out.print(result);

                //добавить длину выхода для полного выполнения
                Double t = new Double(result);
                len += t.toString().length(); //сохранить длину
            }
            lastDelim = token;

            //если запятая, переместиться к следующей точке
            if (lastDelim.equals(",")) {
                //подсчитать число пробелов для табуляции
                spaces = 8 - (len % 8);
                len += spaces; //добавить позицию табуляции
                while (spaces != 0) {
                    System.out.print(" ");
                    spaces--;
                }
            } else if (token.equals(";")) {
                System.out.print(" ");
                len++;
            } else if (kwToken != EOL && !token.equals(EOP))
                handleErr(SYNTAX);
        } while (lastDelim.equals(";") || lastDelim.equals(","));
        if (kwToken == EOL || token.equals(EOP)) {
            if (!lastDelim.equals(";") && !lastDelim.equals(","))
                System.out.println();
        } else
            handleErr(SYNTAX);
    }

    //выполнить утверждение GOTO
    private void execGoto() throws InterpreterException {
        Integer loc;
        getToken(); //получить метку

        //найти положение метки
        loc = (Integer) labelTable.get(token);
        if (loc == null)
            handleErr(UNDEFLABEL); //метка не определена
        else //начать выполнение программы с loc
            progIdx = loc.intValue();
    }

    //выполнить утверждение IF
    private void execIf() throws InterpreterException {
        double result;
        result = evaluate(); //получить значение выражения

        /*Если резултат true (не нуль), обработать IF.
        В противном случае перейти к следующей строке программы.*/
        if (result != 0.0) {
            getToken();
            if (kwToken != THEN) {
                handleErr(THENECPECTED);
                return;
            }
        } else
            findEOL(); //найти начало следующей строки
    }

    //выполнить утверждение FOR
    private void execFor() throws InterpreterException {
        ForInfo stckvar = new ForInfo();
        double value;
        char vname;
        getToken(); //считать контрольную переменную
        vname = token.charAt(0);
        if (!Character.isLetter(vname)) {
            handleErr(NOTVAR);
            return;
        }

        //сохранить индекс контрольной переменной
        stckvar.var = Character.toUpperCase(vname) - 'A';

        getToken(); //считать символ равенства
        if (token.charAt(0) != '=') {
            handleErr(EQUALEXPECTED);
            return;
        }

        value = evaluate(); //инициализировать
        vars[stckvar.var] = value;
        getToken(); // считать и отбросить to
        if (kwToken != TO) handleErr(TOEXPECTED);
        stckvar.target = evaluate(); //получить значение

        /*Если цикл может выполниться по крайней мере один раз,
          то поместить метку в стек */
        if (value >= vars[stckvar.var]) {
            stckvar.loc = progIdx;
            fStack.push(stckvar);
        } else //в противном случае пропустить цикл полностью
            while (kwToken != NEXT) getToken();
    }

    //выполнить утверждение NEXT
    private void next() throws InterpreterException {
        ForInfo stckvar;
        try {
            //извлечь информацию для цикла For
            stckvar = (ForInfo) fStack.pop();
            vars[stckvar.var]++; //инкрементировать управляющую переменную

            //если сделано, вернуться
            if (vars[stckvar.var] > stckvar.target) return;
            //иначе, восстановить информацию
            fStack.push(stckvar);
            progIdx = stckvar.loc; //цикл
        } catch (EmptyStackException e) {
            handleErr(NEXTWITHOUTFOR);
        }
    }

    //выполнить простую форму INPUT
    private void input() throws InterpreterException {
        int var;
        double val = 0.0;
        String str;
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        getToken(); //посмотреть на присутствие приглашения
        if (tokType == QUTEDSTR) {
            //если так, напечатать и проверить запятую
            System.out.print(token);
            getToken();
            if (!token.equals(",")) handleErr(SYNTAX);
            getToken();
        } else
            System.out.print("? "); //иначе сделать приглашение с "?"
    }

    //выполнить утверждение GOSUB
    private void gosub() throws InterpreterException {
        Integer loc;
        getToken();

        //найти метку для вызова
        loc = (Integer) labelTable.get(token);
        if (loc == null)
            handleErr(UNDEFLABEL); //метка не определена
        else {
            //сохранить место для возврата
            gStack.push(new Integer(progIdx));

            //начать выполнение с этого места
            progIdx = loc.intValue();
        }
    }

    //возврат из GOSUB
    private void greturn() throws InterpreterException {
        Integer t;
        try {
            //восстановить индекс программы
            t = (Integer) gStack.pop();
            progIdx = t.intValue();
        } catch (EmptyStackException e) {
            handleErr(RETURNWITHOUTGOSUB);
        }
    }

    //*******************Синтаксический анализатор*******************************
    //входная точка анализатора
    private double evaluate() throws InterpreterException {
        double result = 0.0;
        getToken();
        if (token.equals(EOP))
            handleErr(NOEXP); //выражения нет

        //проанализировать и подсчитать выражение
        result = evalExp1();
        putBack();
        return  result;
    }

    //обработать операторы отношения: <, >, =, <=, >=, <>
    private double evalExp1() throws InterpreterException {
        double l_temp, r_temp, result;
        char op;

        result = evalExp2();

        //если конец программы, то возврат
        if (token.equals(EOP)) return  result;

        op = token.charAt(0);
        if (isRelop(op)) {
            l_temp = result;
            getToken();
            r_temp = evalExp1();

            switch (op) { //выполнить оператор отношения
                case '<':
                    if (l_temp < r_temp) result = 1.0;
                    else result = 0.0;
                    break;
                case LE:
                    if (l_temp <= r_temp) result = 1.0;
                    else result = 0.0;
                    break;
                case '>':
                    if (l_temp > r_temp) result = 1.0;
                    else result = 0.0;
                    break;
                case GE:
                    if (l_temp >= r_temp) result = 1.0;
                    else result = 0.0;
                    break;
                case '=':
                    if (l_temp == r_temp) result = 1.0;
                    else result = 0.0;
                    break;
                case NE:
                    if (l_temp != r_temp) result = 1.0;
                    else result = 0.0;
            }
        }
        return  result;
    }

    //сложить и вычесть два терма
    private double evalExp2() throws InterpreterException {
        char op;
        double result;
        double partialResult;

        result = evalExp3();

        while ((op = token.charAt(0)) == '+' || op == '-') {
            getToken();
            partialResult = evalExp3();
            switch (op) {
                case '-':
                    result = result - partialResult;
                    break;
                case '+':
                    result = result + partialResult;
                    break;
            }
        }
        return result;
    }

    //перемножить и разделить два фактора
    private double evalExp3() throws InterpreterException {
        char op;
        double result;
        double partialResult;

        result = evalExp4();

        while ((op = token.charAt(0)) == '*' || op == '/' || op == '%') {
            getToken();
            partialResult = evalExp4();
            switch (op) {
                case '*':
                    result = result * partialResult;
                    break;
                case '/':
                    if (partialResult == 0.0)
                        handleErr(DIVBYZERO);
                    result = result / partialResult;
                    break;
                case '%':
                    if (partialResult == 0.0)
                        handleErr(DIVBYZERO);
                    result = result % partialResult;
                    break;
            }
        }
        return result;
    }

    //выполнить возведение в степень
    private double evalExp4() throws InterpreterException {
        double result;
        double partialResult;
        double ex;
        int t;

        result = evalExp5();

        if (token.equals("^")) {
            getToken();
            partialResult = evalExp4();
            ex = result;
            if (partialResult == 0.0) {
                result = 1.0;
            } else
                for (t = (int) partialResult - 1; t > 0; t--)
                    result = result * ex;
        }
        return result;
    }

    //применить унарный + или -
    private double evalExp5() throws InterpreterException {
        double result;
        String op;

        op = "";
        if ((tokType == DELIMITER) && token.equals("+") || token.equals("-")) {
            op = token;
            getToken();
        }
        result = evalExp6();

        if (op.equals("-")) result = -result;

        return result;
    }

    //обработать выражение в круглых скобках
    private double evalExp6() throws InterpreterException {
        double result;

        if (token.equals("(")) {
            getToken();
            result = evalExp2();
            if (!token.equals(")"))
                handleErr(UNBALPARENS);
            getToken();
        } else
            result = atom();

        return result;
    }

    //получить значение чилса или переменной
    private double atom() throws InterpreterException {
        double result = 0.0;

        switch (tokType) {
            case NUBMER:
                try {
                    result = Double.parseDouble(token);
                } catch (NumberFormatException e) {
                    handleErr(SYNTAX);
                }
                getToken();
                break;
            case VARIABLE:
                result = findVar(token);
                getToken();
                break;
            default:
                handleErr(SYNTAX);
                break;
        }
        return result;
    }

    //возвратить значение переменной
    private double findVar(String vname) throws InterpreterException {
        if (!Character.isLetter(vname.charAt(0))) {
            handleErr(SYNTAX);
            return 0.0;
        }
        return vars[Character.toUpperCase(vname.charAt(0)) - 'A'];
    }

    //возвратить лексему во входной поток
    private void putBack() {
        if  (token == EOP) return;
        for (int i = 0; i < token.length(); i++)
            progIdx--;
    }

    //обработать ошибку
    private void handleErr(int error) throws InterpreterException {
        String[] err = {
                "Syntax Error",
                "Unbalanced Parentheses",
                "No Expression Present",
                "Division by Zero",
                "Equal sign expected",
                "Not a variable",
                "Label table full",
                "Duplicate label",
                "Underfined label",
                "THEN expected",
                "TO expected",
                "NEXT without FOR",
                "RETURN without GOSUB",
                "Closing quotes needed",
                "File not found",
                "I/O error while loading file",
                "I/O error on INPUT statement"
        };

        throw  new InterpreterException(err[error]);
    }

    //получить следующую лексему
    private void getToken() throws InterpreterException {
        char ch;
        tokType = NONE;
        token = "";
        kwToken = UNKNCOM;

        //проверить конец программы
        if (progIdx == prog.length) {
            token = EOP;
            return;
        }

        //пропустить пробелы
        while (progIdx < prog.length && isSpaceorTab(prog[progIdx]))
            progIdx++;

        //залкючительные пробелы программы
        if (progIdx == prog.length) {
            token = EOP;
            tokType = DELIMITER;
            return;
        }

        //перевод строки
        if (prog[progIdx] == '\r') {
            progIdx += 2;
            kwToken = EOL;
            token = "\r\n";
            return;
        }

        //обработка операторов отношения
        ch = prog[progIdx];
        if (ch == '<' || ch == '>') {
            if (progIdx + 1 == prog.length) handleErr(SYNTAX);

            switch (ch) {
                case '<':
                    if (prog[progIdx + 1] == '>') {
                        progIdx += 2;
                        token = String.valueOf(NE); //не равно <>
                    } else if (prog[progIdx + 1] == '=') {
                        progIdx += 2;
                        token = String.valueOf(LE); //меньше или равно <=
                    } else {
                        progIdx++;
                        token = "<";
                    }
                    break;
                case '>':
                    if (prog[progIdx + 1] == '=') {
                        progIdx +=2;
                        token = String.valueOf(GE); //больше или равно >=
                    } else {
                        progIdx++;
                        token = ">";
                    }
                    break;
            }
            tokType = DELIMITER;
            return;
        }

        if (isDelim(prog[progIdx])) {
            //оператор
            token += prog[progIdx];
            progIdx++;
            tokType = DELIMITER;
        } else if (Character.isLetter(prog[progIdx])) {
            //переменная или ключевое слово
            while (!isDelim(prog[progIdx])) {
                token += prog[progIdx];
                progIdx++;
                if (progIdx >= prog.length) break;
            }
            kwToken = lookUp(token);
            if (kwToken == UNKNCOM) tokType = VARIABLE;
            else tokType = COMMAND;
        } else if (Character.isDigit(prog[progIdx])) {
            //число
            while (!isDelim(prog[progIdx])) {
                token += prog[progIdx];
                progIdx++;
                if (progIdx >= prog.length) break;
            }
            tokType = NUBMER;
        } else  if (prog[progIdx] == '"') {
            //строка в кавычках
            progIdx++;
            ch = prog[progIdx];
            while (ch != '"' && ch != '\r') {
                token += ch;
                progIdx++;
                tokType = QUTEDSTR;
            }
        } else { //неизвестный символ, прервать выполнение
            token = EOP;
            return;
        }
    }

    //возвратить true, если с является разделителем
    private boolean isDelim(char c) {
        if (" \r,;<>+-/*%^=()".indexOf(c) != -1)
            return true;
        return false;
    }

    //ворвратить true, если с является пробелом или символом табуляции
    private boolean isSpaceorTab(char c) {
        if (c == ' ' || c == '\t') return true;
        return false;
    }

    //возвратить если с является оператором отношения
    private boolean isRelop(char c) {
        if (relops.indexOf(c) != -1) return true;
        return false;
    }

    //найти внутреннее представление лексемы в таблице лексем
    private int lookUp(String s) {
        //преобразовать в нижний регистр
        s = s.toLowerCase();

        //проверить лексему в таблице
        for (int i = 0; i < kwTable.length; i++)
            if (kwTable[i].keyword.equals(s))
                return kwTable[i].keywordTok;
        return UNKNCOM; //неизвестное ключевое слово
    }
}

