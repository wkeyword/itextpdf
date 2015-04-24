package com.itextpdf.text.pdf.pdfcleanup;

import com.itextpdf.awt.geom.Point2D;
import com.itextpdf.text.BadElementException;
import com.itextpdf.text.DocWriter;
import com.itextpdf.text.Image;
import com.itextpdf.text.pdf.*;
import com.itextpdf.text.pdf.parser.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

class PdfCleanUpContentOperator implements ContentOperator {

    private static final byte[] TStar = DocWriter.getISOBytes("T*\n");
    private static final byte[] Tw = DocWriter.getISOBytes(" Tw ");
    private static final byte[] TcTStar = DocWriter.getISOBytes(" Tc T*\n");
    private static final byte[] TJ = DocWriter.getISOBytes("] TJ\n");
    private static final byte[] Tc = DocWriter.getISOBytes(" Tc\n");
    private static final byte[] m = DocWriter.getISOBytes(" m\n");
    private static final byte[] l = DocWriter.getISOBytes(" l\n");
    private static final byte[] c = DocWriter.getISOBytes(" c\n");
    private static final byte[] h = DocWriter.getISOBytes("h\n");
    private static final byte[] S = DocWriter.getISOBytes("S\n");
    private static final byte[] f = DocWriter.getISOBytes("f\n");
    private static final byte[] n = DocWriter.getISOBytes("n\n");
    private static final byte[] W = DocWriter.getISOBytes("W\n");

    private static final Set<String> textShowingOperators = new HashSet<String>(Arrays.asList("TJ", "Tj", "'", "\""));
    private static final Set<String> pathConstructionOperators = new HashSet<String>(Arrays.asList("m", "l", "c", "v", "y", "h", "re"));
    private static final Set<String> strokeOperators = new HashSet<String>(Arrays.asList("S", "s", "B", "B*", "b", "b*"));
    private static final Set<String> fillOperators = new HashSet<String>(Arrays.asList("f", "F", "f*", "B", "B*", "b", "b*"));
    private static final Set<String> pathPaintingOperators = new HashSet<String>() {{
        addAll(strokeOperators);
        addAll(fillOperators);
        add("n");
    }};
    private static final Set<String> clippingPathOperators = new HashSet<String>(Arrays.asList("W", "W*"));

    protected PdfCleanUpRenderListener cleanUpStrategy;
    protected ContentOperator originalContentOperator;

    public PdfCleanUpContentOperator(PdfCleanUpRenderListener cleanUpStrategy) {
        this.cleanUpStrategy = cleanUpStrategy;
    }

    public static void populateOperators(PdfContentStreamProcessor contentProcessor,
                                         PdfCleanUpRenderListener pdfCleanUpRenderListener) {
        for (String operator : contentProcessor.getRegisteredOperatorStrings()) {
            PdfCleanUpContentOperator contentOperator = new PdfCleanUpContentOperator(pdfCleanUpRenderListener);
            contentOperator.originalContentOperator = contentProcessor.registerContentOperator(operator, contentOperator);
        }
    }

    public void invoke(PdfContentStreamProcessor pdfContentStreamProcessor, PdfLiteral operator, ArrayList<PdfObject> operands) throws Exception {
        String operatorStr = operator.toString();
        PdfContentByte canvas = cleanUpStrategy.getContext().getCanvas();
        PRStream xFormStream = null;
        boolean disableOutput = pathConstructionOperators.contains(operatorStr) || pathPaintingOperators.contains(operatorStr) || clippingPathOperators.contains(operatorStr);

        // key - number of a string in the TJ operator, value - number following the string; the first number without string (if it's presented) is stored under 0.
        // BE AWARE: zero-length strings are ignored!!!
        Map<Integer, Float> structuredTJoperands = null;

        if ("Do".equals(operatorStr)) {
            if (operands.size() == 2 && operands.get(0).isName()) {
                PdfDictionary xObjResources = cleanUpStrategy.getContext().getResources().getAsDict(PdfName.XOBJECT);

                if (xObjResources != null) {
                    PdfStream xObj = xObjResources.getAsStream((PdfName) operands.get(0));

                    if (xObj instanceof PRStream && xObj.getAsName(PdfName.SUBTYPE) != null &&
                            xObj.getAsName(PdfName.SUBTYPE).compareTo(PdfName.FORM) == 0) {
                        xFormStream = (PRStream) xObj;
                        cleanUpStrategy.registerNewContext(xObj.getAsDict(PdfName.RESOURCES), null);
                    }
                }
            }
        }

        originalContentOperator.invoke(pdfContentStreamProcessor, operator, operands);
        List<PdfCleanUpContentChunk> chunks = cleanUpStrategy.getChunks();

        if (xFormStream != null) {
            xFormStream.setData(cleanUpStrategy.getContext().getCanvas().toPdf(cleanUpStrategy.getContext().getCanvas().getPdfWriter()));
            cleanUpStrategy.popContext();
            canvas = cleanUpStrategy.getContext().getCanvas();
        }

        if ("Do".equals(operatorStr)) {
            if (chunks.size() > 0 && chunks.get(0) instanceof PdfCleanUpContentChunk.Image) {
                PdfCleanUpContentChunk.Image chunk = (PdfCleanUpContentChunk.Image) chunks.get(0);

                if (chunk.isVisible()) {
                    PdfDictionary xObjResources = cleanUpStrategy.getContext().getResources().getAsDict(PdfName.XOBJECT);
                    PRStream imageStream = (PRStream) xObjResources.getAsStream((PdfName) operands.get(0));
                    updateImageStream(imageStream, chunk.getNewImageData());
                } else {
                    disableOutput = true;
                }
            }
        } else if ("q".equals(operatorStr)) {
            cleanUpStrategy.getContext().saveGraphicsState();
        } else if ("Q".equals(operatorStr)) {
            cleanUpStrategy.getContext().restoreGraphicsState();
        } else if ("Tf".equals(operatorStr)) {
            cleanUpStrategy.getContext().setFontSize(((PdfNumber) operands.get(1)).floatValue());
        } else if ("Tc".equals(operatorStr)) {
            cleanUpStrategy.getContext().setCharacterSpacing(((PdfNumber) operands.get(0)).floatValue());
        } else if ("Tw".equals(operatorStr)) {
            cleanUpStrategy.getContext().setWordSpacing(((PdfNumber) operands.get(0)).floatValue());
        } else if ("Tz".equals(operatorStr)) {
            cleanUpStrategy.getContext().setHorizontalScaling(((PdfNumber) operands.get(0)).floatValue());
        } else if (textShowingOperators.contains(operatorStr) && !allChunksAreVisible(cleanUpStrategy.getChunks())) {
            disableOutput = true;

            if ("'".equals(operatorStr)) {
                canvas.getInternalBuffer().append(TStar);
            } else if ("\"".equals(operatorStr)) {
                operands.get(0).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
                canvas.getInternalBuffer().append(Tw);

                operands.get(1).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
                canvas.getInternalBuffer().append(TcTStar);

                cleanUpStrategy.getContext().setWordSpacing(((PdfNumber) operands.get(0)).floatValue());
                cleanUpStrategy.getContext().setCharacterSpacing(((PdfNumber) operands.get(1)).floatValue());
            } else if ("TJ".equals(operatorStr)) {
                structuredTJoperands = structureTJarray((PdfArray) operands.get(0));
            }

            writeTextChunks(structuredTJoperands, chunks, canvas);
        } else if ("\"".equals(operatorStr)) {
            cleanUpStrategy.getContext().setWordSpacing(((PdfNumber) operands.get(0)).floatValue());
            cleanUpStrategy.getContext().setCharacterSpacing(((PdfNumber) operands.get(1)).floatValue());
        } else if (pathPaintingOperators.contains(operatorStr)) {
            writePath(operatorStr, canvas);
        }

        if (!disableOutput) {
            int index = 0;

            for (PdfObject o : operands) {
                toPdf(o, canvas.getPdfWriter(), canvas.getInternalBuffer());
                canvas.getInternalBuffer().append(operands.size() > ++index ? (byte) ' ' : (byte) '\n');
            }
        }

        cleanUpStrategy.clearChunks();
    }

    private boolean allChunksAreVisible(List<PdfCleanUpContentChunk> chunks) {
        for (PdfCleanUpContentChunk chunk : chunks) {
            if (!chunk.isVisible()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Overriding standard PdfObject.toPdf because we need sorted PdfDictionaries.
     */
    private static void toPdf(PdfObject object, PdfWriter writer, OutputStream os) throws IOException {
        if (object instanceof PdfDictionary) {
            os.write('<');
            os.write('<');

            List<PdfName> keys = new ArrayList<PdfName>(((PdfDictionary) object).getKeys());
            Collections.sort(keys);

            for (PdfName key : keys) {
                toPdf(key, writer, os);
                PdfObject value = ((PdfDictionary) object).get(key);
                int type = value.type();

                if (type != PdfObject.ARRAY && type != PdfObject.DICTIONARY && type != PdfObject.NAME &&
                        type != PdfObject.STRING) {
                    os.write(' ');
                }

                toPdf(value, writer, os);
            }

            os.write('>');
            os.write('>');
        } else {
            object.toPdf(writer, os);
        }
    }

    /**
     * Example.
     *      TJ = [(h) 3 4 (q) 7 (w) (e)]
     *      Result = {0:0, 1:7, 2:7, 3:0, 4:0}
     *
     * @return Map whose key is an ordinal number of the string in the TJ array and value
     *         is the position adjustment.
     */
    private Map<Integer, Float> structureTJarray(PdfArray array) {
        Map<Integer, Float> structuredTJoperands = new HashMap<Integer, Float>();

        if (array.size() == 0) {
            return structuredTJoperands;
        }

        Integer previousStrNum = 0;
        structuredTJoperands.put(previousStrNum, 0f);

        for (int i = 0; i < array.size(); ++i) {
            PdfObject currentObj = array.getPdfObject(i);

            if (currentObj instanceof PdfString && ((PdfString) currentObj).toUnicodeString().length() > 0) {
                ++previousStrNum;
                structuredTJoperands.put(previousStrNum, 0f);
            } else {
                Float oldOffset = structuredTJoperands.get(previousStrNum);
                structuredTJoperands.put(previousStrNum, oldOffset + ((PdfNumber) currentObj).floatValue());
            }
        }

        return structuredTJoperands;
    }

    /**
     * Renders parts of text which are visible.
     */
    private void writeTextChunks(Map<Integer, Float> structuredTJoperands, List<PdfCleanUpContentChunk> chunks, PdfContentByte canvas) throws IOException {
        canvas.setCharacterSpacing(0);
        canvas.setWordSpacing(0);
        canvas.getInternalBuffer().append((byte) '[');

        float characterSpacing = cleanUpStrategy.getContext().getCharacterSpacing();
        float convertedCharacterSpacing = -characterSpacing * 1000f / cleanUpStrategy.getContext().getFontSize();

        float wordSpacing = cleanUpStrategy.getContext().getWordSpacing();
        float convertedWordSpacing = -wordSpacing * 1000f / cleanUpStrategy.getContext().getFontSize();

        float shift = structuredTJoperands != null ? structuredTJoperands.get(0) : 0;
        PdfCleanUpContentChunk.Text prevChunk = null;

        for (PdfCleanUpContentChunk chunk : chunks) {
            PdfCleanUpContentChunk.Text textChunk = (PdfCleanUpContentChunk.Text) chunk;

            if (prevChunk != null && prevChunk.getNumOfStrTextBelongsTo() != textChunk.getNumOfStrTextBelongsTo() &&
                    structuredTJoperands != null) {
                shift += structuredTJoperands.get(prevChunk.getNumOfStrTextBelongsTo());
            }

            if (textChunk.isVisible()) {
                if (Float.compare(shift, 0.0f) != 0 && Float.compare(shift, -0.0f) != 0) {
                    canvas.getInternalBuffer().append(shift).append(' ');
                }

                textChunk.getText().toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
                canvas.getInternalBuffer().append(' ');

                shift = convertedCharacterSpacing + (isSpace(textChunk) ? convertedWordSpacing : 0);
            } else {
                shift += getUnscaledTextChunkWidth(textChunk);
            }

            prevChunk = textChunk;
        }

        if (Float.compare(shift, 0.0f) != 0 && Float.compare(shift, -0.0f) != 0) {
            canvas.getInternalBuffer().append(shift);
        }

        canvas.getInternalBuffer().append(TJ);

        if (Float.compare(characterSpacing, 0.0f) != 0 && Float.compare(characterSpacing, -0.0f) != 0) {
            new PdfNumber(characterSpacing).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
            canvas.getInternalBuffer().append(Tc);
        }

        if (Float.compare(wordSpacing, 0.0f) != 0 && Float.compare(wordSpacing, -0.0f) != 0) {
            new PdfNumber(wordSpacing).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
            canvas.getInternalBuffer().append(Tw);
        }
    }

    /**
     * We get into this method when the current chunk is not visible.
     * Here we are calculating a piece of the Tj coefficient for a previous visible chunk.
     * For details see PDF spec., Text Space Details, formula for "tx" coefficient
     * and TextRenderInfo class (getUnscaledBaseline method)
     */
    private float getUnscaledTextChunkWidth(PdfCleanUpContentChunk.Text chunk) {
        PdfCleanUpContext context = cleanUpStrategy.getContext();
        float fontSize = context.getFontSize();
        float characterSpacing = context.getCharacterSpacing();
        float wordSpacing = context.getWordSpacing();
        float horizontalScaling = context.getHorizontalScaling();

        // We should multiply by 100 because iText stores horizontal scaling as the value in [0, 1] interval;
        // also we need to add character and word spaces because TextRenderInfo class truncates them from the end of a string
        // (single character string in our case is also truncated)
        float scaledChunkWidth = (chunk.getEndX() - chunk.getStartX()) * 100f +
                (characterSpacing + (isSpace(chunk) ? wordSpacing : 0)) * horizontalScaling;

        return -scaledChunkWidth * 1000f / (horizontalScaling * fontSize);
    }

    private boolean isSpace(PdfCleanUpContentChunk.Text chunk) {
        return chunk.getText().toUnicodeString().equals(" ");
    }

    private void updateImageStream(PRStream imageStream, byte[] newData) throws BadElementException, IOException, BadPdfFormatException {
        PdfImage image = new PdfImage(Image.getInstance(newData), "", null);

        if (imageStream.contains(PdfName.SMASK)) {
            image.put(PdfName.SMASK, imageStream.get(PdfName.SMASK));
        }

        if (imageStream.contains(PdfName.MASK)) {
            image.put(PdfName.MASK, imageStream.get(PdfName.MASK));
        }

        if (imageStream.contains(PdfName.SMASKINDATA)) {
            image.put(PdfName.SMASKINDATA, imageStream.get(PdfName.SMASKINDATA));
        }

        imageStream.clear();
        imageStream.putAll(image);
        imageStream.setDataRaw(image.getBytes());
    }

    private void writePath(String operatorStr, PdfContentByte canvas) throws IOException { // TODO: refactor
        if (cleanUpStrategy.isClipped()) {
            writePath(cleanUpStrategy.getCurrentFillPath(), null, canvas);
            canvas.getInternalBuffer().append(W);

            if ("n".equals(operatorStr)) {
                canvas.getInternalBuffer().append(n);
                cleanUpStrategy.setClipped(false);
                return;
            }
        }

        if (fillOperators.contains(operatorStr) && cleanUpStrategy.isClipped()) {
            canvas.getInternalBuffer().append(f);
        } else if (fillOperators.contains(operatorStr)) {
            writePath(cleanUpStrategy.getCurrentFillPath(), f, canvas);
        }

        if (strokeOperators.contains(operatorStr)) {
            if (!fillOperators.contains(operatorStr) && cleanUpStrategy.isClipped()) {
                canvas.getInternalBuffer().append(n);
            }

            writePath(cleanUpStrategy.getCurrentStrokePath(), S, canvas);
        }

        cleanUpStrategy.setClipped(false);
    }

    private void writePath(Path path, byte[] pathPaintingOperator, PdfContentByte canvas) throws IOException {
        if (path.getSubpaths().size() == 0) {
            return;
        }

        for (Subpath subpath : path.getSubpaths()) {
            writeMoveTo(subpath.getStartPoint(), canvas);

            for (Shape segment : subpath.getSegments()) {
                if (segment instanceof BezierCurve) {
                    writeBezierCurve((BezierCurve) segment, canvas);
                } else {
                    writeLine((Line) segment, canvas);
                }
            }
        }

        if (pathPaintingOperator != null) {
            canvas.getInternalBuffer().append(pathPaintingOperator);
        }
    }

    private void writeMoveTo(Point2D destinationPoint, PdfContentByte canvas) throws IOException {
        new PdfNumber(destinationPoint.getX()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(' ');
        new PdfNumber(destinationPoint.getY()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(m);
    }

    private void writeBezierCurve(BezierCurve curve, PdfContentByte canvas) throws IOException {
        List<Point2D> basePoints = curve.getBasePoints();
        Point2D p2 = basePoints.get(1);
        Point2D p3 = basePoints.get(2);
        Point2D p4 = basePoints.get(3);

        new PdfNumber(p2.getX()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(' ');

        new PdfNumber(p2.getY()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(' ');

        new PdfNumber(p3.getX()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(' ');

        new PdfNumber(p3.getY()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(' ');

        new PdfNumber(p4.getX()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(' ');

        new PdfNumber(p4.getY()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(c);
    }

    private void writeLine(Line line, PdfContentByte canvas) throws IOException {
        Point2D destination = line.getBasePoints().get(1);

        new PdfNumber(destination.getX()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(' ');

        new PdfNumber(destination.getY()).toPdf(canvas.getPdfWriter(), canvas.getInternalBuffer());
        canvas.getInternalBuffer().append(l);
    }
}