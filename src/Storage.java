import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class Storage {
    private final Path main;
    private final Path backup;
    private final Path temp;

    public Storage(Path directory) {
        this.main = directory.resolve("database.bin");
        this.backup = directory.resolve("database.bak");
        this.temp = directory.resolve("database.tmp");
    }

    public DbEngine.State load() throws DbException {
        if (!Files.exists(main) && !Files.exists(backup)) return null;
        DbException original = null;
        if (Files.exists(main)) {
            try { return read(main); }
            catch (DbException ex) { original = ex; }
        }
        if (Files.exists(backup)) {
            DbEngine.State recovered = read(backup);
            try {
                Files.copy(backup, main, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new DbException("Резервный снимок прочитан, но восстановить основной файл не удалось", ex);
            }
            return recovered;
        }
        if (original != null) throw original;
        throw new DbException("База отсутствует, восстановление невозможно");
    }

    private static DbEngine.State read(Path path) throws DbException {
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(path.toFile()))) {
            Object obj = in.readObject();
            if (!(obj instanceof DbEngine.State))
                throw new DbException("Неверный тип сохранённого состояния: " + path.getFileName());
            DbEngine.State state = (DbEngine.State) obj;
            if (state.tables == null) throw new DbException("Повреждён каталог таблиц");
            return state;
        } catch (IOException | ClassNotFoundException ex) {
            throw new DbException("Не удалось прочитать " + path.getFileName(), ex);
        }
    }

    public void save(DbEngine.State state) throws DbException {
        try {
            Files.createDirectories(main.getParent());
            try (FileOutputStream stream = new FileOutputStream(temp.toFile());
                 ObjectOutputStream out = new ObjectOutputStream(stream)) {
                out.writeObject(state);
                out.flush();
                stream.getFD().sync();
            }
            if (Files.exists(main))
                Files.copy(main, backup, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, main, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, main, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.exists(backup))
                Files.copy(main, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new DbException("Не удалось сохранить базу данных", ex);
        }
    }

    public static DbEngine.State copy(DbEngine.State state) throws DbException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(state);
            }
            try (ObjectInputStream in = new ObjectInputStream(
                    new ByteArrayInputStream(bytes.toByteArray()))) {
                return (DbEngine.State) in.readObject();
            }
        } catch (IOException | ClassNotFoundException ex) {
            throw new DbException("Не удалось скопировать состояние базы", ex);
        }
    }
}