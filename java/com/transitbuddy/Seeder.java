package com.transitbuddy;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Fills the database with 14 days of sample trains and buses the first time the app starts. */
@Component
public class Seeder implements CommandLineRunner {
    private final JdbcTemplate db;
    public Seeder(JdbcTemplate db) { this.db = db; }

    @Override
    public void run(String... args) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM trips", Integer.class);
        if (count != null && count > 0) return;
        Object[][] pairs = {{"Tiruppur","Coimbatore",50},{"Tiruppur","Erode",55},{"Tiruppur","Salem",130},{"Tiruppur","Chennai",450},{"Tiruppur","Madurai",210},
            {"Tiruppur","Bengaluru",390},{"Coimbatore","Chennai",500},{"Coimbatore","Bengaluru",365},{"Madurai","Chennai",460},{"Salem","Chennai",340},{"Erode","Chennai",400},{"Trichy","Chennai",330}};
        Map<String, String[]> names = Map.of("train", new String[]{"Kovai Express","Chennai Mail","Intercity SF"}, "bus", new String[]{"KPN Travels","SETC Ultra Deluxe","TNSTC AC Sleeper"});
        Map<String, int[][]> deps = Map.of("train", new int[][]{{6,10},{13,40},{22,5}}, "bus", new int[][]{{5,30},{8,0},{11,15},{14,45},{18,30},{21,50}});
        List<Object[]> batch = new ArrayList<>();
        for (int d = 0; d < 14; d++)
            for (Object[] p : pairs)
                for (boolean back : new boolean[]{false, true})
                    for (String mode : new String[]{"train", "bus"}) {
                        int km = (int) p[2], speed = mode.equals("train") ? 55 : 45, mins = Math.round(km * 60f / speed) + 15;
                        int[][] times = deps.get(mode);
                        for (int i = 0; i < times.length; i++) {
                            LocalDateTime start = LocalDate.now().plusDays(d).atTime(times[i][0], times[i][1]);
                            int price = (int) (Math.round(km * (mode.equals("train") ? 0.9 : 1.4) * (i % 3 == 2 ? 1.25 : 1) / 5) * 5 + 20);
                            batch.add(new Object[]{mode, names.get(mode)[i % 3], back ? p[1] : p[0], back ? p[0] : p[1], start.toLocalDate().toString(),
                                    start.toString(), start.plusMinutes(mins).toString(), price, mode.equals("train") ? 40 : 32});
                        }
                    }
        db.batchUpdate("INSERT INTO trips(kind,name,from_city,to_city,travel_date,depart,arrive,price,seats) VALUES (?,?,?,?,?,?,?,?,?)", batch);
        System.out.println("Seeded " + batch.size() + " trips");
    }
}
