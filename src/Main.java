import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "data";
        try {
            DbEngine engine = new DbEngine(Paths.get(dir));
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            System.out.println("Учебная СУБД аэропорта. Каталог: " + dir);
            System.out.println("Команды: HELP, SHOW TABLES;, EXIT");
            StringBuilder sql = new StringBuilder();
            while (true) {
                System.out.print(sql.length() == 0 ? "sql> " : "...> ");
                String line = reader.readLine();
                if (line == null) break;
                String trimmed = line.trim();
                if (sql.length() == 0 && (trimmed.equalsIgnoreCase("EXIT")
                        || trimmed.equalsIgnoreCase("QUIT"))) break;
                if (sql.length() == 0 && trimmed.equalsIgnoreCase("HELP")) {
                    help(); continue;
                }
                if (trimmed.isEmpty()) continue;
                sql.append(line).append('\n');
                if (!trimmed.endsWith(";")) continue;
                try {
                    QueryResult result = engine.execute(sql.toString());
                    if (result.hasTable()) printTable(result);
                    System.out.println(result.message);
                    if (result.indexUsed) System.out.println("План: использован индекс.");
                } catch (DbException ex) {
                    System.out.println("Ошибка: " + ex.getMessage());
                } catch (RuntimeException ex) {
                    System.out.println("Неожиданная ошибка: " + ex.getClass().getSimpleName()
                            + " (операция не подтверждена; проверьте данные)");
                }
                sql.setLength(0);
            }
            System.out.println("Завершение работы.");
        } catch (DbException | IOException ex) {
            System.err.println("Не удалось запустить СУБД: " + ex.getMessage());
            System.exit(1);
        }
    }


    private static void help() {
        System.out.println("\n========== СПРАВКА ПО СУБД ==========");

        System.out.println("\nПРОСМОТР ДАННЫХ");
        System.out.println("SHOW TABLES;");
        System.out.println("  Показать список таблиц.");
        System.out.println("SELECT * FROM flights;");
        System.out.println("  Показать все рейсы.");
        System.out.println("SELECT * FROM flights WHERE price > 10000;");
        System.out.println("  Найти рейсы дороже 10000.");

        System.out.println("\nДОБАВЛЕНИЕ ДАННЫХ");
        System.out.println("INSERT INTO airlines (id, name, country) VALUES (1, 'Aeroflot', 'Russia');");
        System.out.println("  Добавить авиакомпанию.");
        System.out.println("INSERT INTO tickets (id, flight_id, passenger) VALUES (1, 1, 'Anna');");
        System.out.println("  Добавить билет на существующий рейс.");

        System.out.println("\nИЗМЕНЕНИЕ ДАННЫХ");
        System.out.println("UPDATE flights SET price = 17000 WHERE id = 1;");
        System.out.println("  Изменить цену рейса.");

        System.out.println("\nУДАЛЕНИЕ ДАННЫХ");
        System.out.println("DELETE FROM tickets WHERE id = 1;");
        System.out.println("  Удалить билет.");

        System.out.println("\nРАБОТА С ТАБЛИЦАМИ");
        System.out.println("CREATE TABLE hotels (id INT PRIMARY KEY, name STRING);");
        System.out.println("  Создать новую таблицу.");
        System.out.println("DROP TABLE hotels;");
        System.out.println("  Удалить таблицу.");

        System.out.println("\nРАБОТА С ИНДЕКСАМИ");
        System.out.println("CREATE INDEX idx_flights_price ON flights (price);");
        System.out.println("  Создать индекс по цене рейса.");
        System.out.println("DROP INDEX idx_flights_price;");
        System.out.println("  Удалить индекс.");

        System.out.println("\nДОПОЛНИТЕЛЬНО");
        System.out.println("Типы данных: INT, DOUBLE, STRING, DATE, BOOLEAN.");
        System.out.println("Дата: ГГГГ-ММ-ДД. Время вылета: ЧЧ:ММ.");
        System.out.println("Каждая SQL-команда заканчивается точкой с запятой (;).");
        System.out.println("HELP — справка. EXIT — выход.");
        System.out.println("====================================\n");
    }


    private static String cell(Object val) { return val == null ? "NULL" : val.toString(); }

    private static void printTable(QueryResult result) {
        int cols = result.columns.size();
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) widths[i] = result.columns.get(i).length();
        for (List<Object> row : result.rows)
            for (int i = 0; i < cols; i++) widths[i] = Math.max(widths[i], cell(row.get(i)).length());
        border(widths);
        printCells(new ArrayList<Object>(result.columns), widths);
        border(widths);
        for (List<Object> row : result.rows) printCells(row, widths);
        border(widths);
    }

    private static void border(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            for (int j = 0; j < w + 2; j++) sb.append('-');
            sb.append('+');
        }
        System.out.println(sb);
    }

    private static void printCells(List<?> cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            String value = cell(cells.get(i));
            sb.append(' ').append(value);
            for (int j = value.length(); j < widths[i]; j++) sb.append(' ');
            sb.append(" |");
        }
        System.out.println(sb);
    }
}