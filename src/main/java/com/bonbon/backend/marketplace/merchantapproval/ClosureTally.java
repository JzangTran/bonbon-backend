package com.bonbon.backend.marketplace.merchantapproval;

/** What the {@link ClosureWork} providers found for one shop; the shop can close only when everything is zero. */
public final class ClosureTally {

    private long inProgressOrders;
    private long openCases;
    private long pendingAppeals;
    private long heldForCases;
    private long balance;

    public void inProgressOrders(long n) {
        inProgressOrders += n;
    }

    public void openCases(long n) {
        openCases += n;
    }

    public void pendingAppeals(long n) {
        pendingAppeals += n;
    }

    public void heldForCases(long amount) {
        heldForCases += amount;
    }

    public void balance(long amount) {
        balance += amount;
    }

    public long inProgressOrders() {
        return inProgressOrders;
    }

    public long openCases() {
        return openCases;
    }

    public long pendingAppeals() {
        return pendingAppeals;
    }

    public long heldForCases() {
        return heldForCases;
    }

    public long balance() {
        return balance;
    }
}
