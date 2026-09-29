package com.saarthi.service.policy;

import java.time.LocalDate;

public class PolicySourceItem {

    private String title;
    private String summary;
    private String source;
    private String sourceUrl;
    private LocalDate publishedDate;

    public PolicySourceItem() {
    }

    public PolicySourceItem(
            String title,
            String summary,
            String source,
            String sourceUrl,
            LocalDate publishedDate
    ) {
        this.title = title;
        this.summary = summary;
        this.source = source;
        this.sourceUrl = sourceUrl;
        this.publishedDate = publishedDate;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public LocalDate getPublishedDate() {
        return publishedDate;
    }

    public void setPublishedDate(LocalDate publishedDate) {
        this.publishedDate = publishedDate;
    }
}