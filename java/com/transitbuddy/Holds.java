package com.transitbuddy;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.*;

/** Keeps temporary 2-minute seat holds in memory and pushes live updates to every open browser. */
@Component
public class Holds {
    public record Hold(String client, long until) {}
    private static final long HOLD_MS = 120_000;
    private final Map<String, Hold> map = new ConcurrentHashMap<>();
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    public Hold get(int trip, int seat) {
        String k = trip + ":" + seat; Hold h = map.get(k);
        if (h != null && h.until() < System.currentTimeMillis()) { map.remove(k); return null; }
        return h;
    }
    public synchronized boolean take(int trip, int seat, String client) {
        Hold h = get(trip, seat);
        if (h != null && !h.client().equals(client)) return false;
        map.put(trip + ":" + seat, new Hold(client, System.currentTimeMillis() + HOLD_MS));
        return true;
    }
    public void release(int trip, int seat, String client) {
        Hold h = get(trip, seat);
        if (h != null && h.client().equals(client)) map.remove(trip + ":" + seat);
    }
    public List<Integer> mine(int trip, String client) {
        List<Integer> seats = new ArrayList<>();
        for (String k : map.keySet()) {
            if (!k.startsWith(trip + ":")) continue;
            int seat = Integer.parseInt(k.substring(k.indexOf(':') + 1));
            Hold h = get(trip, seat);
            if (h != null && h.client().equals(client)) seats.add(seat);
        }
        Collections.sort(seats);
        return seats;
    }
    public SseEmitter subscribe() {
        SseEmitter e = new SseEmitter(0L);
        subscribers.add(e);
        e.onCompletion(() -> subscribers.remove(e));
        e.onTimeout(() -> subscribers.remove(e));
        e.onError(x -> subscribers.remove(e));
        return e;
    }
    public void broadcast(int trip) {
        for (SseEmitter e : subscribers) {
            try { e.send(SseEmitter.event().name("seats").data(String.valueOf(trip))); }
            catch (Exception ex) { subscribers.remove(e); }
        }
    }
}
