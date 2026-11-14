import java.io.Serializable;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public final class DbEngine {
    enum Type { INT, DOUBLE, STRING, DATE, BOOLEAN }

    static final class Column implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name;
        final Type type;
        final boolean primary;
        final String parentTable;
        final String parentColumn;
        Column(String name, Type type, boolean primary, String parentTable, String parentColumn) {
            this.name = name; this.type = type; this.primary = primary;
            this.parentTable = parentTable; this.parentColumn = parentColumn;
        }
    }

    static final class Index implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name;
        final String column;
        final TreeMap<Object, LinkedHashSet<Object>> entries = new TreeMap<>();
        Index(String name, String column) { this.name = name; this.column = column; }
        void insert(Object value, Object primaryKey) {
            if (value == null) return;
            LinkedHashSet<Object> pks = entries.get(value);
            if (pks == null) { pks = new LinkedHashSet<>(); entries.put(value, pks); }
            pks.add(primaryKey);
        }
    }

    static final class Table implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name;
        final LinkedHashMap<String, Column> columns = new LinkedHashMap<>();
        final LinkedHashMap<Object, LinkedHashMap<String, Object>> rows = new LinkedHashMap<>();
        final LinkedHashMap<String, Index> indexes = new LinkedHashMap<>();
        String primaryKey;
        Table(String name) { this.name = name; }
    }

    static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        final LinkedHashMap<String, Table> tables = new LinkedHashMap<>();
    }

    private final Storage storage;
    private State state;

    public DbEngine(Path dataDirectory) throws DbException {
        storage = new Storage(dataDirectory);
        State loaded = storage.load();
        if (loaded == null) {
            State initial = new State();
            createTable(initial, SqlParser.parse("CREATE TABLE airlines (id INT PRIMARY KEY, "
                    + "name STRING, country STRING);") );
            createTable(initial, SqlParser.parse("CREATE TABLE flights (id INT PRIMARY KEY, "
                    + "code STRING, departure DATE, price DOUBLE, active BOOLEAN, "
                    + "airline_id INT REFERENCES airlines(id), departure_city STRING, "
                    + "arrival_city STRING, departure_time STRING);") );
            createTable(initial, SqlParser.parse("CREATE TABLE tickets (id INT PRIMARY KEY, "
                    + "flight_id INT REFERENCES flights(id), passenger STRING, "
                    + "checked_in BOOLEAN, baggage_kg DOUBLE);") );
            verifyConstraints(initial);
            storage.save(initial);
            state = initial;
        } else {
            verifyConstraints(loaded);
            State upgraded = Storage.copy(loaded);
            if (upgradeAirportSchema(upgraded)) {
                verifyConstraints(upgraded);
                storage.save(upgraded);
                state = upgraded;
            } else {
                state = loaded;
            }
        }
    }

    private static boolean upgradeAirportSchema(State s) throws DbException {
        Table flights = s.tables.get("flights");
        if (flights == null || !flights.columns.containsKey("id")
                || !flights.columns.containsKey("code")
                || !flights.columns.containsKey("departure")
                || !flights.columns.containsKey("price")
                || !flights.columns.containsKey("active")) return false;

        boolean changed = false;
        if (!s.tables.containsKey("airlines")) {
            createTable(s, SqlParser.parse("CREATE TABLE airlines (id INT PRIMARY KEY, "
                    + "name STRING, country STRING);") );
            changed = true;
        }
        Table airlines = table(s, "airlines");
        if (!"id".equals(airlines.primaryKey) || column(airlines, "id").type != Type.INT)
            throw new DbException("Для связи рейсов нужна таблица airlines с первичным ключом id INT");
        changed |= addColumnIfMissing(flights, "airline_id", Type.INT, "airlines", "id");
        changed |= addColumnIfMissing(flights, "departure_city", Type.STRING, null, null);
        changed |= addColumnIfMissing(flights, "arrival_city", Type.STRING, null, null);
        changed |= addColumnIfMissing(flights, "departure_time", Type.STRING, null, null);
        return changed;
    }

    private static boolean addColumnIfMissing(Table t, String name, Type type,
                                               String parentTable, String parentColumn) {
        if (t.columns.containsKey(name)) return false;
        t.columns.put(name, new Column(name, type, false, parentTable, parentColumn));
        for (LinkedHashMap<String, Object> row : t.rows.values()) row.put(name, null);
        return true;
    }

    public synchronized QueryResult execute(String sql) throws DbException {
        SqlParser.Command cmd = SqlParser.parse(sql);
        switch (cmd.kind) {
            case SELECT: return select(state, cmd);
            case SHOW_TABLES: return showTables(state);
            default:
                State next = Storage.copy(state);
                QueryResult result;
                switch (cmd.kind) {
                    case CREATE_TABLE: createTable(next, cmd); result = QueryResult.message("Таблица создана: " + cmd.table); break;
                    case DROP_TABLE: dropTable(next, cmd.table); result = QueryResult.message("Таблица удалена: " + cmd.table); break;
                    case CREATE_INDEX: createIndex(next, cmd); result = QueryResult.message("Индекс создан: " + cmd.indexName); break;
                    case DROP_INDEX: dropIndex(next, cmd.indexName); result = QueryResult.message("Индекс удалён: " + cmd.indexName); break;
                    case INSERT: result = insert(next, cmd); break;
                    case UPDATE: result = update(next, cmd); break;
                    case DELETE: result = delete(next, cmd); break;
                    default: throw new DbException("Неподдерживаемая команда");
                }
                verifyConstraints(next);
                storage.save(next);
                state = next;
                return result;
        }
    }

    private static Table table(State s, String name) throws DbException {
        Table t = s.tables.get(name);
        if (t == null) throw new DbException("Таблица не найдена: " + name);
        return t;
    }

    private static Column column(Table t, String name) throws DbException {
        Column c = t.columns.get(name);
        if (c == null) throw new DbException("Нет столбца " + name + " в таблице " + t.name);
        return c;
    }

    private static Type type(String input) throws DbException {
        switch (input.toUpperCase(Locale.ROOT)) {
            case "INT": case "INTEGER": return Type.INT;
            case "DOUBLE": case "FLOAT": case "REAL": return Type.DOUBLE;
            case "STRING": case "TEXT": case "VARCHAR": return Type.STRING;
            case "DATE": return Type.DATE;
            case "BOOLEAN": case "BOOL": return Type.BOOLEAN;
            default: throw new DbException("Неподдерживаемый тип столбца: " + input);
        }
    }

    private static void createTable(State s, SqlParser.Command cmd) throws DbException {
        if (s.tables.containsKey(cmd.table)) throw new DbException("Таблица уже существует: " + cmd.table);
        Table t = new Table(cmd.table);
        int keyCount = 0;
        for (SqlParser.ColumnSpec spec : cmd.definitions) {
            if (t.columns.containsKey(spec.name)) throw new DbException("Повторяется столбец: " + spec.name);
            Type dataType = type(spec.type);
            if (spec.primary) { keyCount++; t.primaryKey = spec.name; }
            if (spec.primary && spec.parentTable != null)
                throw new DbException("Первичный ключ одновременно не может быть внешним в этой версии");
            if (spec.parentTable != null) {
                Table parent = table(s, spec.parentTable);
                if (!parent.primaryKey.equals(spec.parentColumn))
                    throw new DbException("REFERENCES должен указывать на первичный ключ родителя");
                if (column(parent, spec.parentColumn).type != dataType)
                    throw new DbException("Тип внешнего ключа не совпадает с типом родителя");
            }
            t.columns.put(spec.name, new Column(spec.name, dataType, spec.primary,
                    spec.parentTable, spec.parentColumn));
        }
        if (keyCount != 1) throw new DbException("Требуется ровно один PRIMARY KEY");
        s.tables.put(t.name, t);
    }

    private static void dropTable(State s, String name) throws DbException {
        table(s, name);
        for (Table t : s.tables.values()) {
            if (t.name.equals(name)) continue;
            for (Column col : t.columns.values()) {
                if (name.equals(col.parentTable))
                    throw new DbException("Таблица " + name + " используется внешним ключом " + t.name + "." + col.name);
            }
        }
        s.tables.remove(name);
    }

    private static void createIndex(State s, SqlParser.Command cmd) throws DbException {
        Table t = table(s, cmd.table);
        column(t, cmd.indexColumn);
        for (Table each : s.tables.values()) {
            if (each.indexes.containsKey(cmd.indexName))
                throw new DbException("Имя индекса занято: " + cmd.indexName);
        }
        for (Index ix : t.indexes.values()) {
            if (ix.column.equals(cmd.indexColumn))
                throw new DbException("Столбец уже индексирован: " + cmd.indexColumn);
        }
        t.indexes.put(cmd.indexName, new Index(cmd.indexName, cmd.indexColumn));
        rebuildIndexes(t);
    }

    private static void dropIndex(State s, String name) throws DbException {
        for (Table t : s.tables.values()) {
            if (t.indexes.remove(name) != null) return;
        }
        throw new DbException("Индекс не найден: " + name);
    }

    private static void rebuildIndexes(Table t) {
        for (Index index : t.indexes.values()) index.entries.clear();
        for (Map.Entry<Object, LinkedHashMap<String, Object>> item : t.rows.entrySet()) {
            for (Index index : t.indexes.values())
                index.insert(item.getValue().get(index.column), item.getKey());
        }
    }

    private static Object value(SqlParser.Literal lit, Type expected) throws DbException {
        if (lit == null || (!lit.quoted && lit.value.equalsIgnoreCase("NULL"))) return null;
        try {
            switch (expected) {
                case INT:
                    if (lit.quoted || !lit.value.matches("-?[0-9]+")) break;
                    return Integer.parseInt(lit.value);
                case DOUBLE:
                    if (lit.quoted || !lit.value.matches("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) break;
                    double d = Double.parseDouble(lit.value);
                    if (!Double.isFinite(d)) throw new DbException("Бесконечность и NaN не допускаются");
                    return d == 0.0 ? 0.0 : d;
                case STRING:
                    if (lit.quoted) return lit.value;
                    break;
                case DATE:
                    if (lit.quoted) return LocalDate.parse(lit.value);
                    break;
                case BOOLEAN:
                    if (!lit.quoted && (lit.value.equalsIgnoreCase("TRUE") || lit.value.equalsIgnoreCase("FALSE")))
                        return Boolean.parseBoolean(lit.value);
                    break;
                default: break;
            }
        } catch (NumberFormatException | DateTimeParseException ex) {
            throw new DbException("Недопустимое значение для " + expected + ": " + lit.value);
        }
        throw new DbException("Неверный тип литерала '" + lit.value + "', требуется " + expected);
    }

    private static QueryResult insert(State s, SqlParser.Command cmd) throws DbException {
        Table t = table(s, cmd.table);
        List<String> fieldNames = cmd.columns.isEmpty() ? new ArrayList<>(t.columns.keySet()) : cmd.columns;
        LinkedHashSet<String> unique = new LinkedHashSet<>(fieldNames);
        if (unique.size() != fieldNames.size()) throw new DbException("Повторяются столбцы INSERT");
        for (String name : fieldNames) column(t, name);
        int count = 0;
        for (List<SqlParser.Literal> raw : cmd.values) {
            if (raw.size() != fieldNames.size()) throw new DbException("Число значений не совпадает с числом столбцов");
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (Column col : t.columns.values()) row.put(col.name, null);
            for (int i = 0; i < fieldNames.size(); i++) {
                String name = fieldNames.get(i);
                row.put(name, value(raw.get(i), column(t, name).type));
            }
            Object pk = row.get(t.primaryKey);
            if (pk == null) throw new DbException("Первичный ключ не может быть NULL: " + t.primaryKey);
            if (t.rows.containsKey(pk)) throw new DbException("Дубликат первичного ключа: " + pk);
            t.rows.put(pk, row);
            count++;
        }
        rebuildIndexes(t);
        return QueryResult.message("Вставлено записей: " + count);
    }

    private static final class PreparedCondition {
        final String column;
        final String operator;
        final Object value;
        PreparedCondition(String column, String operator, Object value) {
            this.column = column; this.operator = operator; this.value = value;
        }
    }

    private static PreparedCondition prepare(Table t, SqlParser.Condition where) throws DbException {
        if (where == null) return null;
        Column col = column(t, where.column);
        if (where.operator.equals("IS NULL") || where.operator.equals("IS NOT NULL"))
            return new PreparedCondition(where.column, where.operator, null);
        Object expected = value(where.value, col.type);
        if (expected == null)
            throw new DbException("Для проверки NULL используйте IS NULL / IS NOT NULL");
        if (col.type == Type.BOOLEAN && !where.operator.equals("=")
                && !where.operator.equals("!=") && !where.operator.equals("<>"))
            throw new DbException("Для BOOLEAN допускаются только =, !=, <>");
        return new PreparedCondition(where.column, where.operator, expected);
    }

    private static boolean matches(LinkedHashMap<String, Object> row, PreparedCondition c) {
        if (c == null) return true;
        Object actual = row.get(c.column);
        if (c.operator.equals("IS NULL")) return actual == null;
        if (c.operator.equals("IS NOT NULL")) return actual != null;
        if (actual == null) return false;
        int order = ((Comparable<Object>) actual).compareTo(c.value);
        switch (c.operator) {
            case "=": return order == 0;
            case "!=": case "<>": return order != 0;
            case "<": return order < 0;
            case "<=": return order <= 0;
            case ">": return order > 0;
            case ">=": return order >= 0;
            default: throw new IllegalStateException("Неожиданный оператор WHERE");
        }
    }

    private static List<LinkedHashMap<String, Object>> findRows(Table t, PreparedCondition condition,
                                                                 boolean allowIndex, boolean[] indexUsed) {
        List<LinkedHashMap<String, Object>> found = new ArrayList<>();
        if (allowIndex && condition != null && condition.value != null
                && condition.column.equals(t.primaryKey) && condition.operator.equals("=")) {
            indexUsed[0] = true;
            LinkedHashMap<String, Object> row = t.rows.get(condition.value);
            if (row != null) found.add(row);
            return found;
        }
        if (allowIndex && condition != null && condition.value != null
                && !condition.operator.equals("!=") && !condition.operator.equals("<>")) {
            for (Index index : t.indexes.values()) {
                if (!index.column.equals(condition.column)) continue;
                indexUsed[0] = true;
                NavigableMap<Object, LinkedHashSet<Object>> selected;
                switch (condition.operator) {
                    case "=":
                        LinkedHashSet<Object> keys = index.entries.get(condition.value);
                        if (keys != null) for (Object pk : keys) found.add(t.rows.get(pk));
                        return found;
                    case "<": selected = index.entries.headMap(condition.value, false); break;
                    case "<=": selected = index.entries.headMap(condition.value, true); break;
                    case ">": selected = index.entries.tailMap(condition.value, false); break;
                    case ">=": selected = index.entries.tailMap(condition.value, true); break;
                    default: indexUsed[0] = false; selected = null;
                }
                if (selected != null) {
                    for (LinkedHashSet<Object> keys2 : selected.values())
                        for (Object pk : keys2) found.add(t.rows.get(pk));
                    return found;
                }
            }
        }
        for (LinkedHashMap<String, Object> row : t.rows.values())
            if (matches(row, condition)) found.add(row);
        return found;
    }

    private static QueryResult select(State s, SqlParser.Command cmd) throws DbException {
        Table t = table(s, cmd.table);
        List<String> fields = cmd.allColumns ? new ArrayList<>(t.columns.keySet()) : cmd.columns;
        for (String field : fields) column(t, field);
        PreparedCondition c = prepare(t, cmd.condition);
        boolean[] indexed = {false};
        List<LinkedHashMap<String, Object>> chosen = findRows(t, c, true, indexed);
        List<List<Object>> results = new ArrayList<>();
        for (LinkedHashMap<String, Object> row : chosen) {
            List<Object> projected = new ArrayList<>();
            for (String field : fields) projected.add(row.get(field));
            results.add(projected);
        }
        return QueryResult.table(fields, results, indexed[0]);
    }

    private static QueryResult update(State s, SqlParser.Command cmd) throws DbException {
        Table t = table(s, cmd.table);
        PreparedCondition c = prepare(t, cmd.condition);
        LinkedHashMap<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, SqlParser.Literal> item : cmd.assignments.entrySet())
            changes.put(item.getKey(), value(item.getValue(), column(t, item.getKey()).type));
        int changed = 0;
        LinkedHashMap<Object, LinkedHashMap<String, Object>> updated = new LinkedHashMap<>();
        for (LinkedHashMap<String, Object> old : t.rows.values()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(old);
            if (matches(old, c)) { row.putAll(changes); changed++; }
            Object pk = row.get(t.primaryKey);
            if (pk == null) throw new DbException("Первичный ключ не может быть NULL");
            if (updated.putIfAbsent(pk, row) != null)
                throw new DbException("Изменение создаёт дубликат первичного ключа: " + pk);
        }
        t.rows.clear(); t.rows.putAll(updated);
        rebuildIndexes(t);
        return QueryResult.message("Обновлено записей: " + changed);
    }

    private static QueryResult delete(State s, SqlParser.Command cmd) throws DbException {
        Table t = table(s, cmd.table);
        PreparedCondition c = prepare(t, cmd.condition);
        int deleted = 0;
        java.util.Iterator<Map.Entry<Object, LinkedHashMap<String, Object>>> it = t.rows.entrySet().iterator();
        while (it.hasNext()) {
            if (matches(it.next().getValue(), c)) { it.remove(); deleted++; }
        }
        rebuildIndexes(t);
        return QueryResult.message("Удалено записей: " + deleted);
    }

    private static void verifyConstraints(State s) throws DbException {
        for (Table t : s.tables.values()) {
            if (t == null || t.columns == null || t.rows == null || t.indexes == null
                    || t.primaryKey == null || !t.columns.containsKey(t.primaryKey))
                throw new DbException("Повреждена структура таблицы");
            for (Map.Entry<Object, LinkedHashMap<String, Object>> item : t.rows.entrySet()) {
                if (item.getKey() == null || item.getValue() == null
                        || !Objects.equals(item.getKey(), item.getValue().get(t.primaryKey)))
                    throw new DbException("Нарушен первичный ключ таблицы " + t.name);
                if ("flights".equals(t.name) && t.columns.containsKey("departure_time")) {
                    Object time = item.getValue().get("departure_time");
                    if (time != null && (!(time instanceof String)
                            || !((String) time).matches("([01][0-9]|2[0-3]):[0-5][0-9]")))
                        throw new DbException("Неверное время вылета: " + time
                                + ". Ожидается ЧЧ:ММ (00:00–23:59)");
                }
                for (Column col : t.columns.values()) {
                    Object foreign = item.getValue().get(col.name);
                    if (col.parentTable == null || foreign == null) continue;
                    Table parent = table(s, col.parentTable);
                    if (!parent.rows.containsKey(foreign))
                        throw new DbException("Нарушен внешний ключ " + t.name + "." + col.name
                                + ": значение " + foreign + " отсутствует в " + parent.name);
                }
            }
        }
    }

    private static QueryResult showTables(State s) {
        List<List<Object>> result = new ArrayList<>();
        for (String name : s.tables.keySet()) result.add(Collections.<Object>singletonList(name));
        return QueryResult.table(Collections.singletonList("table_name"), result, false);
    }

}