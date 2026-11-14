import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QueryResult {
    public final String message;
    public final List<String> columns;
    public final List<List<Object>> rows;
    public final boolean indexUsed;

    private QueryResult(String message, List<String> columns, List<List<Object>> rows, boolean indexUsed) {
        this.message = message;
        this.columns = columns;
        this.rows = rows;
        this.indexUsed = indexUsed;
    }

    public static QueryResult message(String message) {
        return new QueryResult(message, Collections.<String>emptyList(),
                Collections.<List<Object>>emptyList(), false);
    }

    public static QueryResult table(List<String> columns, List<List<Object>> rows, boolean indexUsed) {
        List<List<Object>> copy = new ArrayList<>();
        for (List<Object> row : rows) {
            copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        return new QueryResult(rows.size() + " строк(и).", 
                Collections.unmodifiableList(new ArrayList<>(columns)),
                Collections.unmodifiableList(copy), indexUsed);
    }

    public boolean hasTable() { return !columns.isEmpty(); }
}