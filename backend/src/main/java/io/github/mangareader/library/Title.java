package io.github.mangareader.library;

import io.github.mangareader.source.SourceModels.SourceTitleSummary;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "titles")
public class Title {
    @Id
    private UUID id;
    private UUID workId;
    private String sourceId;
    private String externalTitleId;
    private String sourceName;
    private String title;
    private String titleRu;
    private String titleEn;
    private String publicationStatus;
    private Integer releaseYear;
    private String author;
    private String coverUrl;
    private String description;
    private String sourceUrl;
    private String licenseCode;
    private String licenseUrl;

    protected Title() {}

    UUID id() { return id; }

    SourceTitleSummary summary() {
        return new SourceTitleSummary(sourceId, externalTitleId, sourceName, title, author, coverUrl,
                titleRu, titleEn, publicationStatus, releaseYear, workId);
    }
}
