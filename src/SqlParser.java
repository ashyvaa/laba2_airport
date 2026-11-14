import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SqlParser {
    enum Kind { CREATE_TABLE, DROP_TABLE, CREATE_INDEX, DROP_INDEX,
        INSERT, SELECT, UPDATE, DELETE, SHOW_TABLES }

    static final class Literal {
        final String value;
        final boolean quoted;
        Literal(String value, boolean quoted) { this.value = value; this.quoted = quoted; }
    }

    static final class ColumnSpec {
        final String name;
        final String type;
        final boolean primary;
        final String parentTable;
        final String parentColumn;
        ColumnSpec(String name, String type, boolean primary,
                   String parentTable, String parentColumn) {
            this.name = name; this.type = type; this.primary = primary;
            this.parentTable = parentTable; this.parentColumn = parentColumn;
        }
    }

    static final class Condition {
        final String column;
        final String operator;
        final Literal value;
        Condition(String column, String operator, Literal value) {
            this.column = column; this.operator = operator; this.value = value;
        }
    }

    static final class Command {
        final Kind kind;
        String table;
        String indexName;
        String indexColumn;
        final List<ColumnSpec> definitions = new ArrayList<>();
        final List<String> columns = new ArrayList<>();
        final List<List<Literal>> values = new ArrayList<>();
        final Map<String, Literal> assignments = new LinkedHashMap<>();
        Condition condition;
        boolean allColumns;
        Command(Kind kind) { this.kind = kind; }
    }

    private static final class Token {
        final String value;
        final int kind;
        Token(String value, int kind) { this.value = value; this.kind = kind; }
    }

    private final List<Token> tokens;
    private int pos;

    private SqlParser(String sql) throws DbException {
        tokens = tokenize(sql);
    }

    static Command parse(String sql) throws DbException {
        SqlParser p = new SqlParser(sql);
        Command c = p.parseCommand();
        p.symbol(";");
        if (p.peek().kind != 4) throw p.error("После ';' не должно быть других команд");
        return c;
    }

    private static List<Token> tokenize(String sql) throws DbException {
        if (sql == null) throw new DbException("Запрос отсутствует");
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch)) { i++; continue; }
            if (ch == '\'') {
                i++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (i < sql.length()) {
                    char v = sql.charAt(i++);
                    if (v == '\'') {
                        if (i < sql.length() && sql.charAt(i) == '\'') { sb.append('\''); i++; }
                        else { closed = true; break; }
                    } else sb.append(v);
                }
                if (!closed) throw new DbException("Незакрытая строка в одинарных кавычках");
                out.add(new Token(sb.toString(), 2));
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int from = i++;
                while (i < sql.length()) {
                    char t = sql.charAt(i);
                    if (Character.isLetterOrDigit(t) || t == '_') i++;
                    else break;
                }
                out.add(new Token(sql.substring(from, i), 0));
                continue;
            }
            if (Character.isDigit(ch) || (ch == '-' && i + 1 < sql.length()
                    && Character.isDigit(sql.charAt(i + 1)))) {
                int from = i;
                if (ch == '-') i++;
                while (i < sql.length() && Character.isDigit(sql.charAt(i))) i++;
                if (i < sql.length() && sql.charAt(i) == '.') {
                    i++;
                    if (i >= sql.length() || !Character.isDigit(sql.charAt(i)))
                        throw new DbException("Некорректное вещественное число");
                    while (i < sql.length() && Character.isDigit(sql.charAt(i))) i++;
                }
                if (i < sql.length() && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
                    i++;
                    if (i < sql.length() && (sql.charAt(i) == '+' || sql.charAt(i) == '-')) i++;
                    if (i >= sql.length() || !Character.isDigit(sql.charAt(i)))
                        throw new DbException("Некорректный показатель степени числа");
                    while (i < sql.length() && Character.isDigit(sql.charAt(i))) i++;
                }
                out.add(new Token(sql.substring(from, i), 1));
                continue;
            }
            if ("(),;*=<>!".indexOf(ch) >= 0) {
                String op = Character.toString(ch);
                if ((ch == '<' || ch == '>' || ch == '!') && i + 1 < sql.length()) {
                    char next = sql.charAt(i + 1);
                    if (next == '=' || (ch == '<' && next == '>')) { op += next; i++; }
                }
                if (op.equals("!")) throw new DbException("Оператор '!' без '=' не поддерживается");
                out.add(new Token(op, 3)); i++; continue;
            }
            throw new DbException("Недопустимый символ в SQL: '" + ch + "'");
        }
        out.add(new Token("<конец>", 4));
        return out;
    }

    private Token peek() { return tokens.get(pos); }
    private boolean isWord(String word) {
        return peek().kind == 0 && peek().value.equalsIgnoreCase(word);
    }
    private boolean takeWord(String word) {
        if (!isWord(word)) return false;
        pos++; return true;
    }
    private void word(String word) throws DbException {
        if (!takeWord(word)) throw error("Ожидалось ключевое слово " + word);
    }
    private boolean takeSymbol(String symbol) {
        if (peek().kind != 3 || !peek().value.equals(symbol)) return false;
        pos++; return true;
    }
    private void symbol(String symbol) throws DbException {
        if (!takeSymbol(symbol)) throw error("Ожидался символ '" + symbol + "'");
    }
    private String name() throws DbException {
        if (peek().kind != 0) throw error("Ожидалось имя таблицы или столбца");
        return tokens.get(pos++).value.toLowerCase(Locale.ROOT);
    }
    private Literal literal() throws DbException {
        Token t = peek();
        if (t.kind != 0 && t.kind != 1 && t.kind != 2)
            throw error("Ожидалось значение");
        pos++;
        return new Literal(t.value, t.kind == 2);
    }
    private DbException error(String what) {
        return new DbException(what + ", получено: " + peek().value);
    }
    private void readNames(List<String> names) throws DbException {
        names.add(name());
        while (takeSymbol(",")) names.add(name());
    }
    private List<Literal> valueTuple() throws DbException {
        symbol("(");
        List<Literal> row = new ArrayList<>();
        row.add(literal());
        while (takeSymbol(",")) row.add(literal());
        symbol(")");
        return row;
    }
    private Condition where() throws DbException {
        if (!takeWord("WHERE")) return null;
        String col = name();
        if (takeWord("IS")) {
            boolean not = takeWord("NOT");
            word("NULL");
            return new Condition(col, not ? "IS NOT NULL" : "IS NULL", null);
        }
        String operator = peek().value;
        if (!operator.equals("=") && !operator.equals("!=") && !operator.equals("<>")
                && !operator.equals("<") && !operator.equals("<=")
                && !operator.equals(">") && !operator.equals(">="))
            throw error("Неверный оператор WHERE");
        pos++;
        return new Condition(col, operator, literal());
    }

    private Command parseCommand() throws DbException {
        if (takeWord("CREATE")) {
            if (takeWord("TABLE")) {
                Command c = new Command(Kind.CREATE_TABLE);
                c.table = name(); symbol("(");
                do {
                    String column = name();
                    String type = name().toUpperCase(Locale.ROOT);
                    if (type.equals("VARCHAR") && takeSymbol("(")) {
                        Token length = peek();
                        if (length.kind != 1 || !length.value.matches("[0-9]+"))
                            throw error("Ожидалась длина VARCHAR");
                        pos++; symbol(")");
                    }
                    boolean primary = false;
                    String refTable = null, refColumn = null;
                    boolean another = true;
                    while (another) {
                        another = false;
                        if (takeWord("PRIMARY")) {
                            word("KEY"); primary = true; another = true;
                        } else if (takeWord("REFERENCES")) {
                            refTable = name(); symbol("("); refColumn = name(); symbol(")");
                            another = true;
                        }
                    }
                    c.definitions.add(new ColumnSpec(column, type, primary, refTable, refColumn));
                } while (takeSymbol(","));
                symbol(")"); return c;
            }
            if (takeWord("INDEX")) {
                Command c = new Command(Kind.CREATE_INDEX);
                c.indexName = name(); word("ON"); c.table = name();
                symbol("("); c.indexColumn = name(); symbol(")"); return c;
            }
            throw error("После CREATE ожидается TABLE или INDEX");
        }
        if (takeWord("DROP")) {
            if (takeWord("TABLE")) {
                Command c = new Command(Kind.DROP_TABLE); c.table = name(); return c;
            }
            if (takeWord("INDEX")) {
                Command c = new Command(Kind.DROP_INDEX); c.indexName = name(); return c;
            }
            throw error("После DROP ожидается TABLE или INDEX");
        }
        if (takeWord("INSERT")) {
            Command c = new Command(Kind.INSERT);
            word("INTO"); c.table = name();
            if (takeSymbol("(")) { readNames(c.columns); symbol(")"); }
            word("VALUES");
            c.values.add(valueTuple());
            while (takeSymbol(",")) c.values.add(valueTuple());
            return c;
        }
        if (takeWord("SELECT")) {
            Command c = new Command(Kind.SELECT);
            if (takeSymbol("*")) c.allColumns = true;
            else readNames(c.columns);
            word("FROM"); c.table = name(); c.condition = where(); return c;
        }
        if (takeWord("UPDATE")) {
            Command c = new Command(Kind.UPDATE);
            c.table = name(); word("SET");
            do {
                String column = name(); symbol("=");
                if (c.assignments.put(column, literal()) != null)
                    throw new DbException("Столбец SET указан дважды: " + column);
            } while (takeSymbol(","));
            c.condition = where(); return c;
        }
        if (takeWord("DELETE")) {
            Command c = new Command(Kind.DELETE);
            word("FROM"); c.table = name(); c.condition = where(); return c;
        }
        if (takeWord("SHOW")) { word("TABLES"); return new Command(Kind.SHOW_TABLES); }
        throw error("Неизвестная SQL-команда");
    }
}