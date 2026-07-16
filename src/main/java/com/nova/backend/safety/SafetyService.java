package com.nova.backend.safety;

import com.nova.backend.common.ModuleStateStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SafetyService {

    private final Set<String> blockedUsers = new LinkedHashSet<>();
    private final List<SafetyActionResponse> recentActions = new ArrayList<>();
    private final ModuleStateStore moduleStateStore;

    public SafetyService(ModuleStateStore moduleStateStore) {
        this.moduleStateStore = moduleStateStore;
        loadPersistedState();
    }

    public SafetyActionResponse report(ReportRequest request) {
        SafetyActionResponse response = new SafetyActionResponse("reported", "Report submitted for " + request.targetUserId());
        recentActions.add(0, response);
        persistState();
        return response;
    }

    public SafetyActionResponse block(BlockRequest request) {
        if (request.blocked()) {
            blockedUsers.add(request.targetUserId());
        } else {
            blockedUsers.remove(request.targetUserId());
        }
        SafetyActionResponse response = new SafetyActionResponse(request.blocked() ? "blocked" : "unblocked", request.targetUserId());
        recentActions.add(0, response);
        persistState();
        return response;
    }

    public List<AdminMetricResponse> metrics() {
        return List.of(
                new AdminMetricResponse("Active users", "18.4K", "+8.1%"),
                new AdminMetricResponse("Open reports", "14", "-3 today"),
                new AdminMetricResponse("Call success rate", "94%", "24h"),
                new AdminMetricResponse("Message delivery", "99.2%", "Realtime")
        );
    }

    public SafetyDashboardResponse dashboard() {
        return new SafetyDashboardResponse(metrics(), new ArrayList<>(recentActions));
    }

    private void loadPersistedState() {
        moduleStateStore.load("safety", SafetyState.class).ifPresentOrElse(state -> {
            blockedUsers.clear();
            if (state.blockedUsers() != null) {
                blockedUsers.addAll(state.blockedUsers());
            }
            recentActions.clear();
            if (state.recentActions() != null) {
                recentActions.addAll(state.recentActions());
            }
        }, this::seedDefaults);
    }

    private void seedDefaults() {
        recentActions.add(new SafetyActionResponse("reviewed", "2 reports reviewed today"));
        recentActions.add(new SafetyActionResponse("verified", "1 identity verified"));
        recentActions.add(new SafetyActionResponse("blocked", "1 account blocked"));
        persistState();
    }

    private void persistState() {
        moduleStateStore.save("safety", new SafetyState(new LinkedHashSet<>(blockedUsers), new ArrayList<>(recentActions)));
    }

    private record SafetyState(Set<String> blockedUsers, List<SafetyActionResponse> recentActions) {
    }
}
