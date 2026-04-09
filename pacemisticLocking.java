import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FoodWorker {

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String DB_USER = "postgres";
    // We omit password as usually local connections trust or have no password for 'postgres' 
    // unless pga_hba.conf is modified. We'll use password if needed, but standard brew install trusts localhost.

    public static void main(String[] args) {
        int numberOfThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

        System.out.println("Starting experiment with 50 threads...");

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                processFoodRow(threadId);
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
            System.out.println("Experiment Finished!");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void processFoodRow(int threadId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            // Disable autocommit to manually manage the transaction and locks
            conn.setAutoCommit(false);

            // Step 1: Select the very first row that is available using an EXCLUSIVE lock, skipping locked rows
            String selectSql = "SELECT id FROM food WHERE Taken = '-' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED";
            int foundId = -1;

            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql);
                 ResultSet rs = selectStmt.executeQuery()) {
                
                if (rs.next()) {
                    foundId = rs.getInt("id");
                }
            }

            if (foundId == -1) {
                System.out.println("Thread " + threadId + ": No available food found.");
                conn.commit();
                return;
            }

            // Introduce a very tiny artificial delay.
            // This ensures multiple threads read the same row and acquire the SHARED lock
            // before any thread can obtain the EXCLUSIVE lock needed to update it.
            // This is the classic deadlock trap!
            Thread.sleep(10); 

            // Step 2: Now try to UPDATE that row (requires upgrading to EXCLUSIVE lock)
            String updateSql = "UPDATE food SET Taken = '*' WHERE id = ?";
            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                updateStmt.setInt(1, foundId);
                int rowsAffected = updateStmt.executeUpdate();

                if (rowsAffected > 0) {
                    System.out.println("Thread " + threadId + ": Successfully claimed Food Item #" + foundId);
                } else {
                    System.out.println("Thread " + threadId + ": Failed to claim Food Item #" + foundId);
                }
            }

            conn.commit();

        } catch (SQLException e) {
            // We catch and print the specific deadlock exceptions
            System.err.println("Thread " + threadId + ": EXCEPTION - " + e.getMessage().replaceAll("\n", " "));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
