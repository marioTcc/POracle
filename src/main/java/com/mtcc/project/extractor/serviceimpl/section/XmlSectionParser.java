package com.mtcc.project.extractor.serviceimpl.section;

import io.vavr.control.Try;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;
import reactor.core.Exceptions;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class XmlSectionParser implements SectionParser {

    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    @Override
    public TextSection parse(final String content) {
        final Document document = Try.of(() -> {
                    final DocumentBuilder documentBuilder = createDocumentBuilder();
                    documentBuilder.setErrorHandler(new DefaultHandler());
                    return documentBuilder.parse(new InputSource(new StringReader(content)));
                })
                .getOrElseThrow(Exceptions::propagate);
        final Transformer transformer = Try.of(this::createTransformer)
                .getOrElseThrow(Exceptions::propagate);

        return TextSection.builder()
                .text(content.strip())
                .children(getChildren(document.getDocumentElement(), transformer))
                .build();
    }

    private List<TextSection> getChildren(final Element parent, final Transformer transformer) {
        final List<TextSection> children = new ArrayList<>();
        final StringBuilder comments = new StringBuilder();

        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Comment) {
                comments.append(toXml(node, transformer)).append("\n");
            } else if (node instanceof Element element) {
                children.add(TextSection.builder()
                        .label(element.getTagName())
                        .text(comments + toXml(element, transformer))
                        .children(getChildren(element, transformer))
                        .build());
                comments.setLength(0);
            }
        }
        return children;
    }

    private String toXml(final Node node, final Transformer transformer) {
        final StringWriter xml = new StringWriter();

        Try.run(() -> transformer.transform(new DOMSource(node), new StreamResult(xml)))
                .getOrElseThrow(Exceptions::propagate);
        return xml.toString();
    }

    private DocumentBuilder createDocumentBuilder() throws ParserConfigurationException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
        factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
        factory.setFeature(LOAD_EXTERNAL_DTD, false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private Transformer createTransformer() throws TransformerException {
        final TransformerFactory factory = TransformerFactory.newInstance();

        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");

        final Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        return transformer;
    }
}
