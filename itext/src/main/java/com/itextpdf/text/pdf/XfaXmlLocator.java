package com.itextpdf.text.pdf;

import com.itextpdf.text.DocumentException;
import com.itextpdf.text.pdf.security.XmlLocator;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Helps to locate xml stream inside PDF document with Xfa form.
 */
public class XfaXmlLocator implements XmlLocator {

    public XfaXmlLocator(PdfStamper stamper) throws DocumentException, IOException {
        this.stamper = stamper;
        try {
            createXfaForm();
        } catch (ParserConfigurationException e) {
            throw new DocumentException(e);
        } catch (SAXException e) {
            throw new DocumentException(e);
        }
    }

    private PdfStamper stamper;
    private XfaForm xfaForm;

    protected void createXfaForm() throws ParserConfigurationException, SAXException, IOException {
        xfaForm = new XfaForm(stamper.getReader());
    }

    /**
     * Gets Document to sign
     */
    public Document getDocument() {
        return xfaForm.getDomDocument();
    }

    /**
     * Save document as single XML stream in AcroForm.
     * @param document signed document
     * @throws IOException
     * @throws DocumentException
     */
    public void setDocument(Document document) throws IOException, DocumentException {

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            TransformerFactory tf = TransformerFactory.newInstance();

            Transformer trans = tf.newTransformer();

            //Convert Document to byte[] to save to PDF
            trans.transform(new DOMSource(document), new StreamResult(outputStream));
            //Create PdfStream
            PdfIndirectReference iref = stamper.getWriter().
                    addToBody(new PdfStream(outputStream.toByteArray())).getIndirectReference();
            stamper.getReader().getAcroForm().put(PdfName.XFA, iref);
        } catch (TransformerConfigurationException e) {
            throw new DocumentException(e);
        } catch (TransformerException e) {
            throw new DocumentException(e);
        }
    }
}