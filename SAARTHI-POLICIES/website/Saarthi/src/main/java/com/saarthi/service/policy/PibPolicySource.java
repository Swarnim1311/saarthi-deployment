package com.saarthi.service.policy;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class PibPolicySource implements PolicySource {

    private static final String PIB_RSS_URL =
            "https://www.pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=3";

    private static final String SOURCE_NAME = "PIB";

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<PolicySourceItem> fetch() {

        List<PolicySourceItem> items = new ArrayList<>();

        try {
            URL url = URI.create(PIB_RSS_URL).toURL();

            try (InputStream inputStream = url.openStream()) {

                DocumentBuilderFactory factory =
                        DocumentBuilderFactory.newInstance();

                factory.setNamespaceAware(false);

                DocumentBuilder builder =
                        factory.newDocumentBuilder();

                Document document =
                        builder.parse(inputStream);

                document.getDocumentElement().normalize();

                NodeList rssItems =
                        document.getElementsByTagName("item");

                System.out.println(
                        "PIB RSS items found: " + rssItems.getLength()
                );

                for (int i = 0; i < rssItems.getLength(); i++) {

                    Node node = rssItems.item(i);

                    if (node.getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }

                    Element element = (Element) node;

                    String title =
                            getElementText(element, "title");

                    String summary =
                            getElementText(element, "description");

                    String link =
                            getElementText(element, "link");

                    if (title.isBlank() || link.isBlank()) {
                        continue;
                    }

                    if (!isAgricultureRelated(title, summary)) {
                        continue;
                    }

                    PolicySourceItem item =
                            new PolicySourceItem(
                                    title,
                                    summary,
                                    SOURCE_NAME,
                                    link,
                                    LocalDate.now()
                            );

                    items.add(item);
                }

                System.out.println(
                        "PIB agriculture items matched: " + items.size()
                );
            }

        } catch (Exception e) {

            System.err.println(
                    "Failed to fetch PIB RSS: "
                            + e.getClass().getSimpleName()
                            + " - "
                            + e.getMessage()
            );
        }

        return items;
    }

    private String getElementText(
            Element element,
            String tagName
    ) {

        NodeList nodes =
                element.getElementsByTagName(tagName);

        if (nodes.getLength() == 0) {
            return "";
        }

        Node node = nodes.item(0);

        if (node == null) {
            return "";
        }

        return node.getTextContent().trim();
    }

    private boolean isAgricultureRelated(
            String title,
            String summary
    ) {

        String text =
                (title + " " + summary)
                        .toLowerCase();

        String[] keywords = {

                // English
                "agriculture",
                "agricultural",
                "farmer",
                "farmers",
                "farming",
                "crop",
                "crops",
                "kisan",
                "cultivation",
                "irrigation",
                "fertilizer",
                "fertiliser",
                "pesticide",
                "horticulture",
                "agri",
                "agricultural ministry",
                "ministry of agriculture",
                "animal husbandry",
                "dairy",
                "fisheries",
                "livestock",
                "food grain",
                "food grains",
                "rice",
                "wheat",
                "maize",
                "soybean",
                "farmland",
                "farm",

                // Hindi
                "कृषि",
                "किसान",
                "किसानों",
                "कृषक",
                "खेती",
                "फसल",
                "फसलों",
                "सिंचाई",
                "उर्वरक",
                "खाद",
                "कीटनाशक",
                "बागवानी",
                "कृषि क्षेत्र",
                "कृषि उत्पादन",
                "कृषि मंत्रालय",
                "कृषि मंत्री",
                "पशुपालन",
                "डेयरी",
                "मत्स्य",
                "मछली",
                "मछलियों",
                "पशुधन",
                "अनाज",
                "धान",
                "गेहूं",
                "मक्का",
                "सोयाबीन",
                "कृषि विभाग",
                "कृषि योजना",
                "कृषि योजनाएं",
                "किसान योजना",
                "किसान योजनाएं"
        };

        for (String keyword : keywords) {

            if (text.contains(keyword)) {
                return true;
            }
        }

        return false;
    }
}

