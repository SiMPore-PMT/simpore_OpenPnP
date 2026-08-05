import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Placement;

public class DispensePlacementTest {
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
}
