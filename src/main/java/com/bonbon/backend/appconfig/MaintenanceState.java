package com.bonbon.backend.appconfig;

/** Whether announced maintenance is under way right now (a level other than NONE, inside its start and end time). */
public interface MaintenanceState {

    boolean active();
}
