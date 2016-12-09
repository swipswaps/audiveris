//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                       S t e m S c a l e r                                      //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Hervé Bitteur and others 2000-2016. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet.stem;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.Shape;

import omr.image.ImageUtil;

import omr.math.IntegerPeakFunction;
import omr.math.Range;

import omr.run.Orientation;
import omr.run.Run;
import omr.run.RunTable;
import omr.run.RunTableFactory;

import omr.sheet.PageCleaner;
import omr.sheet.Picture;
import omr.sheet.Scale;
import omr.sheet.Scale.StemScale;
import omr.sheet.Sheet;
import omr.sheet.SystemInfo;

import omr.sig.SIGraph;
import omr.sig.inter.Inter;

import omr.util.StopWatch;

import ij.process.ByteProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Class {@code StemScaler} retrieves the typical thickness of stems in a sheet.
 * <p>
 * Computation is based on histogram of lengths of horizontal runs.
 * For precise results, we have to "remove" the detected bar lines (and connections) otherwise their
 * horizontal runs might impact stem thickness measurement.
 *
 * @author Hervé Bitteur
 */
public class StemScaler
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(StemScaler.class);

    //~ Instance fields ----------------------------------------------------------------------------
    //
    /** Related sheet. */
    private final Sheet sheet;

    /** Keeper of horizontal run length histogram. */
    private HistoKeeper histoKeeper;

    /** Histogram on horizontal foreground runs. */
    private IntegerPeakFunction histo;

    /** Most frequent length of horizontal foreground runs found, if any. */
    private Range peak;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Creates a new StemScaler object.
     *
     * @param sheet the sheet to be processed
     */
    public StemScaler (Sheet sheet)
    {
        this.sheet = sheet;
    }

    //~ Methods ------------------------------------------------------------------------------------
    //--------------//
    // displayChart //
    //--------------//
    /**
     * Display the scale histograms.
     */
    public void displayChart ()
    {
        if (histoKeeper == null) {
            retrieveStemWidth();
        }

        if (histoKeeper != null) {
            histoKeeper.writePlot();
        } else {
            logger.info("No stem data available yet");
        }
    }

    //-------------------//
    // retrieveStemWidth //
    //-------------------//
    /**
     * Retrieve the global stem thickness for the sheet.
     *
     * @return the stem scale data
     */
    public StemScale retrieveStemWidth ()
    {
        StopWatch watch = new StopWatch("Stem scaler for " + sheet.getId());

        try {
            // Use a buffer with bar lines and connections removed
            watch.start("getBuffer");

            Picture picture = sheet.getPicture();
            ByteProcessor buffer = getBuffer();

            // Look at histogram for stem thickness
            watch.start("stem retrieval");

            RunTableFactory runFactory = new RunTableFactory(Orientation.HORIZONTAL);
            RunTable horiTable = runFactory.createTable(buffer);
            histoKeeper = new HistoKeeper(picture.getWidth() - 1);
            histoKeeper.buildHistograms(horiTable, picture.getHeight());

            return computeStem();
        } finally {
            if (constants.printWatch.isSet()) {
                watch.print();
            }
        }
    }

    //-------------//
    // computeStem //
    //-------------//
    private StemScale computeStem ()
    {
        final int area = histo.getArea();
        final List<Range> stemPeaks = histo.getHiLoPeaks(
                constants.minGainRatio.getValue(),
                (int) Math.rint(area * constants.minValueRatio.getValue()),
                (int) Math.rint(area * constants.minDerivativeRatio.getValue()));

        if (!stemPeaks.isEmpty()) {
            peak = stemPeaks.get(0);
        }

        final int mainStem;
        final int maxStem;

        if (peak != null) {
            mainStem = (int) Math.rint(peak.main);
            maxStem = (int) Math.rint(peak.max);
        } else {
            Scale scale = sheet.getScale();
            double ratio = constants.stemAsForeRatio.getValue();
            mainStem = (int) Math.rint(ratio * scale.getMainFore());
            maxStem = (int) Math.rint(ratio * scale.getMaxFore());
            logger.info("No stem peak found, computing defaults");
        }

        return new StemScale(mainStem, maxStem);
    }

    //-----------//
    // getBuffer //
    //-----------//
    private ByteProcessor getBuffer ()
    {
        Picture picture = sheet.getPicture();
        ByteProcessor buf = picture.getSource(Picture.SourceKey.NO_STAFF);
        BufferedImage img = buf.getBufferedImage();
        StemsCleaner eraser = new StemsCleaner(buf, img.createGraphics(), sheet);
        eraser.eraseShapes(
                Arrays.asList(
                        Shape.THICK_BARLINE,
                        Shape.THICK_CONNECTOR,
                        Shape.THIN_BARLINE,
                        Shape.THIN_CONNECTOR));
        buf = new ByteProcessor(img);
        buf.threshold(127); // Binarize

        // Keep a copy on disk?
        if (constants.keepStemImage.isSet()) {
            ImageUtil.saveOnDisk(img, sheet.getId() + ".stem");
        }

        return buf;
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final Constant.Boolean printWatch = new Constant.Boolean(
                false,
                "Should we print the StopWatch on stem computation?");

        private final Constant.Boolean keepStemImage = new Constant.Boolean(
                false,
                "Should we store stem images on disk?");

        private final Constant.Boolean useHeader = new Constant.Boolean(
                true,
                "Should we erase the header at system start");

        private final Scale.Fraction systemVerticalMargin = new Scale.Fraction(
                2.0,
                "Margin erased above & below system header area");

        private final Constant.Ratio minValueRatio = new Constant.Ratio(
                0.1,
                "Absolute ratio of total pixels for peak acceptance");

        private final Constant.Ratio minDerivativeRatio = new Constant.Ratio(
                0.05,
                "Absolute ratio of total pixels for strong derivative");

        private final Constant.Ratio minGainRatio = new Constant.Ratio(
                0.1,
                "Minimum ratio of peak runs for stem peak extension");

        private final Constant.Ratio stemAsForeRatio = new Constant.Ratio(
                1.0,
                "Default stem thickness defined as ratio of foreground peak");
    }

    //-------------//
    // HistoKeeper //
    //-------------//
    /**
     * Handles the histogram of horizontal foreground runs.
     */
    private class HistoKeeper
    {
        //~ Instance fields ------------------------------------------------------------------------

        // We are not interested of horizontal runs longer than this value
        private final int maxBlack = 20;

        //~ Constructors ---------------------------------------------------------------------------
        /**
         * Create an instance of histoKeeper.
         *
         * @param wMax the maximum possible horizontal run length value
         */
        public HistoKeeper (int wMax)
        {
            histo = new IntegerPeakFunction("stem", 0, wMax);
        }

        //~ Methods --------------------------------------------------------------------------------
        //
        //-----------//
        // writePlot //
        //-----------//
        public void writePlot ()
        {
            final String title = sheet.getId() + " " + histo.name;
            histo.new Plotter(title, "Stem thickness", maxBlack, peak).plot(
                    new Point(80, 80));
        }

        //-----------------//
        // buildHistograms //
        //-----------------//
        private void buildHistograms (RunTable horiTable,
                                      int height)
        {
            for (int y = 0; y < height; y++) {
                for (Iterator<Run> it = horiTable.iterator(y); it.hasNext();) {
                    final int blackLength = it.next().getLength();

                    if (blackLength <= maxBlack) {
                        histo.addValue(blackLength, 1);
                    }
                }
            }

            if (logger.isDebugEnabled()) {
                histo.print(System.out);
            }
        }
    }

    //--------------//
    // StemsCleaner //
    //--------------//
    private class StemsCleaner
            extends PageCleaner
    {
        //~ Constructors ---------------------------------------------------------------------------

        public StemsCleaner (ByteProcessor buffer,
                             Graphics2D g,
                             Sheet sheet)
        {
            super(buffer, g, sheet);
        }

        //~ Methods --------------------------------------------------------------------------------
        //-------------//
        // eraseShapes //
        //-------------//
        /**
         * Erase from image graphics all instances of provided shapes.
         *
         * @param shapes (input) the shapes to look for
         */
        public void eraseShapes (Collection<Shape> shapes)
        {
            for (SystemInfo system : sheet.getSystems()) {
                final SIGraph sig = system.getSig();
                final List<Inter> erased = new ArrayList<Inter>();

                for (Inter inter : sig.vertexSet()) {
                    if (!inter.isDeleted() && shapes.contains(inter.getShape())) {
                        if (canHide(inter)) {
                            erased.add(inter);
                        }
                    }
                }

                // Erase the inters
                for (Inter inter : erased) {
                    inter.accept(this);
                }

                // Erase system header?
                if (constants.useHeader.isSet()) {
                    eraseSystemHeader(system, constants.systemVerticalMargin);
                }
            }
        }
    }
}
