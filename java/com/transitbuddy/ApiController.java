package com.transitbuddy;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.security.SecureRandom;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class ApiController {
    record HoldReq(int tripId, int seat, String client) {}
    record BookReq(int tripId, String client, String name, String phone, String gender, String method, Map<String, String> pay) {}
    static class Fail extends RuntimeException {
        final int code;
        Fail(int code, String msg) { super(msg); this.code = code; }
    }

    private final JdbcTemplate db;
    private final Holds holds;
    public ApiController(JdbcTemplate db, Holds holds) { this.db = db; this.holds = holds; }

    @ExceptionHandler(Fail.class)
    ResponseEntity<Map<String, String>> fail(Fail f) { return ResponseEntity.status(f.code).body(Map.of("error", f.getMessage())); }

    /** Seat rules: every 5th seat (1, 6, 11...) is for ladies, every 10th is an easy-access seat. */
    static String seatType(int n) { return n % 5 == 1 ? "ladies" : n % 10 == 0 ? "access" : "std"; }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return db.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            var md = rs.getMetaData();
            for (int c = 1; c <= md.getColumnCount(); c++) m.put(md.getColumnLabel(c).toLowerCase(), rs.getObject(c));
            return m;
        }, args);
    }

    @GetMapping("/cities")
    public List<String> cities() { return db.queryForList("SELECT DISTINCT from_city FROM trips ORDER BY from_city", String.class); }

    @GetMapping("/trips")
    public List<Map<String, Object>> trips(@RequestParam String from, @RequestParam String to, @RequestParam String date, @RequestParam(defaultValue = "") String mode) {
        String sql = "SELECT t.*, t.kind AS mode, t.seats - (SELECT COUNT(*) FROM bookings b WHERE b.trip_id = t.id AND b.status = 'CONFIRMED') AS free "
                + "FROM trips t WHERE from_city = ? AND to_city = ? AND travel_date = ?" + (mode.isEmpty() ? "" : " AND kind = ?") + " ORDER BY depart";
        return mode.isEmpty() ? rows(sql, from, to, date) : rows(sql, from, to, date, mode);
    }

    @GetMapping("/trips/{id}/seats")
    public List<Map<String, Object>> seats(@PathVariable int id, @RequestParam String client) {
        int total;
        try { total = db.queryForObject("SELECT seats FROM trips WHERE id = ?", Integer.class, id); }
        catch (Exception e) { throw new Fail(404, "Trip not found"); }
        Set<Integer> booked = new HashSet<>(db.queryForList("SELECT seat FROM bookings WHERE trip_id = ? AND status = 'CONFIRMED'", Integer.class, id));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int n = 1; n <= total; n++) {
            Holds.Hold h = holds.get(id, n);
            String status = booked.contains(n) ? "booked" : h == null ? "free" : h.client().equals(client) ? "mine" : "held";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", n); m.put("type", seatType(n)); m.put("status", status); m.put("until", status.equals("mine") ? h.until() : null);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/stream")
    public SseEmitter stream() { return holds.subscribe(); }

    @PostMapping("/hold")
    public Map<String, Object> hold(@RequestBody HoldReq r) {
        Integer taken = db.queryForObject("SELECT COUNT(*) FROM bookings WHERE trip_id = ? AND seat = ? AND status = 'CONFIRMED'", Integer.class, r.tripId(), r.seat());
        if (taken != null && taken > 0) throw new Fail(409, "Someone just took this seat");
        if (holds.get(r.tripId(), r.seat()) == null && holds.mine(r.tripId(), r.client()).size() >= 6) throw new Fail(400, "You can hold up to 6 seats");
        if (!holds.take(r.tripId(), r.seat(), r.client())) throw new Fail(409, "Someone just took this seat");
        holds.broadcast(r.tripId());
        return Map.of("ok", 1);
    }

    @PostMapping("/release")
    public Map<String, Object> release(@RequestBody HoldReq r) {
        holds.release(r.tripId(), r.seat(), r.client());
        holds.broadcast(r.tripId());
        return Map.of("ok", 1);
    }

    /** DEMO payment check. Replace with Razorpay / Stripe verification to accept real money. */
    static boolean pay(String method, Map<String, String> d) {
        if ("upi".equals(method)) return d.getOrDefault("upi", "").matches("^[\\w.\\-]{2,}@[a-zA-Z]{2,}$");
        if ("card".equals(method)) return d.getOrDefault("card", "").replace(" ", "").matches("\\d{16}") && d.getOrDefault("exp", "").matches("\\d{2}/\\d{2}");
        return "wallet".equals(method);
    }

    @PostMapping("/book")
    @Transactional
    public Map<String, Object> book(@RequestBody BookReq r) {
        List<Map<String, Object>> t = rows("SELECT * FROM trips WHERE id = ?", r.tripId());
        if (t.isEmpty()) throw new Fail(404, "Trip not found");
        List<Integer> seats = holds.mine(r.tripId(), r.client());
        if (seats.isEmpty()) throw new Fail(400, "Your seat hold expired. Pick seats again.");
        if (r.name() == null || r.name().isBlank() || r.phone() == null || !r.phone().matches("\\d{10}")) throw new Fail(400, "Enter a name and a 10-digit phone number");
        if (!"F".equals(r.gender()) && seats.stream().anyMatch(n -> seatType(n).equals("ladies"))) throw new Fail(400, "Ladies seats can only be booked for women passengers");
        if (!pay(r.method(), r.pay() == null ? Map.of() : r.pay())) throw new Fail(402, "Payment failed. Check your payment details.");
        int price = ((Number) t.get(0).get("price")).intValue();
        String pnr = "TB" + (10_000_000 + new SecureRandom().nextInt(90_000_000));
        try {
            for (int n : seats)
                db.update("INSERT INTO bookings(pnr, trip_id, seat, name, phone, gender, method, amount, seat_key) VALUES (?,?,?,?,?,?,?,?,?)",
                        pnr, r.tripId(), n, r.name(), r.phone(), r.gender(), r.method(), price, r.tripId() + ":" + n);
        } catch (DuplicateKeyException e) { throw new Fail(409, "A seat was just booked by someone else"); }
        seats.forEach(n -> holds.release(r.tripId(), n, r.client()));
        holds.broadcast(r.tripId());
        return Map.of("pnr", pnr, "seats", seats, "total", seats.size() * price, "trip", t.get(0));
    }

    private List<Map<String, Object>> lookup(String pnr) {
        return rows("SELECT b.*, t.name AS tname, t.kind AS mode, t.from_city, t.to_city, t.depart, t.arrive FROM bookings b JOIN trips t ON t.id = b.trip_id WHERE b.pnr = ?", pnr);
    }

    @GetMapping("/pnr/{pnr}")
    public List<Map<String, Object>> pnr(@PathVariable String pnr) {
        var found = lookup(pnr.toUpperCase());
        if (found.isEmpty()) throw new Fail(404, "PNR not found");
        return found;
    }

    @PostMapping("/pnr/{pnr}/cancel")
    public Map<String, Object> cancel(@PathVariable String pnr) {
        String p = pnr.toUpperCase();
        var active = lookup(p).stream().filter(x -> "CONFIRMED".equals(x.get("status"))).toList();
        if (active.isEmpty()) throw new Fail(404, "No active booking for this PNR");
        db.update("UPDATE bookings SET status = 'CANCELLED', seat_key = NULL WHERE pnr = ?", p);
        holds.broadcast(((Number) active.get(0).get("trip_id")).intValue());
        int sum = active.stream().mapToInt(x -> ((Number) x.get("amount")).intValue()).sum();
        return Map.of("refund", Math.round(sum * 0.8));
    }
}
