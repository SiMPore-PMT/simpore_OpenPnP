import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Placement;
import org.openpnp.machine.reference.ReferencePnpJobProcessor;
import org.openpnp.spi.PnpJobProcessor.JobPlacement;

public class DispensePlacementTest {
    private static class TestJobProcessor extends ReferencePnpJobProcessor {
        JobPlacement add(BoardLocation boardLocation, Placement placement) {
            JobPlacement jobPlacement = new JobPlacement(boardLocation, placement);
            jobPlacements.add(jobPlacement);
            return jobPlacement;
        }

        int openPnpWorkCount() {
            return getOpenPendingJobPlacements().size();
        }

        int currentPnpRank() {
            return getCurrentRank();
        }
    }

    @Test
    public void dispensePlacementsDoNotCountAsActivePlacementsButCanStorePlacedStatus() throws Exception {
        Job job = new Job();
        Board board = new Board();

        Placement placement = new Placement("R1");
        placement.setSide(Side.Top);
        board.addPlacement(placement);

        Placement dispense = new Placement("D1");
        dispense.setType(Placement.Type.Dispense);
        dispense.setSide(Side.Top);
        board.addPlacement(dispense);

        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setGlobalSide(Side.Top);
        job.addBoardOrPanelLocation(boardLocation);

        assertEquals(Placement.Type.Dispense, dispense.getType());
        StringWriter serializedPlacement = new StringWriter();
        Configuration.createSerializer().write(dispense, serializedPlacement);
        assertTrue(serializedPlacement.toString().contains("type=\"Dispense\""));

        assertEquals(1, job.getTotalActivePlacements(boardLocation));
        assertEquals(1, job.getActivePlacements(boardLocation));

        assertFalse(job.retrievePlacedStatus(boardLocation, dispense.getId()));
        job.storePlacedStatus(boardLocation, dispense.getId(), true);
        assertTrue(job.retrievePlacedStatus(boardLocation, dispense.getId()));

        assertEquals(1, job.getTotalActivePlacements(boardLocation));
        assertEquals(1, job.getActivePlacements(boardLocation));
    }

    @Test
    public void dispenseRuntimeEntriesAreBoardInstanceSpecificAndExcludedFromPnpWork() {
        Board board = new Board();
        Placement dispense = new Placement("D1");
        dispense.setType(Placement.Type.Dispense);
        dispense.setRank(0);
        board.addPlacement(dispense);

        BoardLocation first = new BoardLocation(board);
        BoardLocation second = new BoardLocation(board);
        TestJobProcessor processor = new TestJobProcessor();
        JobPlacement firstRuntime = processor.add(first, dispense);
        JobPlacement secondRuntime = processor.add(second, dispense);

        assertSame(firstRuntime, processor.getJobPlacement(first, "D1"));
        assertSame(secondRuntime, processor.getJobPlacement(second, "D1"));
        assertNull(processor.getJobPlacement(first, "missing"));
        assertEquals(0, processor.openPnpWorkCount());
        assertEquals(Placement.defaultRank, processor.currentPnpRank());
    }

    @Test
    public void dispenseRuntimeEntryUsesExistingStatusAndErrorApis() {
        Placement dispense = new Placement("D1");
        dispense.setType(Placement.Type.Dispense);
        BoardLocation boardLocation = new BoardLocation(new Board());
        TestJobProcessor processor = new TestJobProcessor();
        JobPlacement runtime = processor.add(boardLocation, dispense);

        assertEquals(JobPlacement.Status.Pending, runtime.getStatus());
        runtime.setStatus(JobPlacement.Status.Complete);
        assertEquals(JobPlacement.Status.Complete, runtime.getStatus());

        Exception failure = new Exception("dispense failed");
        runtime.setError(failure);
        assertEquals(JobPlacement.Status.Errored, runtime.getStatus());
        assertSame(failure, runtime.getError());
    }
}
