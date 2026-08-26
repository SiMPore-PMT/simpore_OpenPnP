package org.openpnp.gui.operator;

import org.openpnp.model.Job;

public class OperatorJobReset {
    public static void resetForFreshOperatorRun(Job job) throws Exception {
        new OperatorJobEditingService().resetJob(job, true);
    }
}
