package com.keevo.feedback.tutorial.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Tutorial catalog settings ({@code keevo.help.tutorials}), v1s-14-6 (FR7). Video URLs are
 * deployment configuration so a video can be published without an app release.
 */
@ConfigurationProperties("keevo.help")
public class TutorialProperties {

    private List<Entry> tutorials = new ArrayList<>();

    public List<Entry> getTutorials() { return tutorials; }
    public void setTutorials(List<Entry> tutorials) { this.tutorials = tutorials; }

    public static class Entry {
        private String id;
        private String module;
        private String title;
        private String description = "";
        private int durationSeconds = 120;
        private String url = "";

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getModule() { return module; }
        public void setModule(String module) { this.module = module; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getDurationSeconds() { return durationSeconds; }
        public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}
