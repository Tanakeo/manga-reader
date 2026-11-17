package io.github.mangareader.library;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LibraryEntryRepository extends JpaRepository<LibraryEntry, UUID> {
    boolean existsByTitleId(UUID titleId);

    @EntityGraph(attributePaths = "title")
    List<LibraryEntry> findAllByOrderByUpdatedAtDescIdDesc();

    @EntityGraph(attributePaths = "title")
    Optional<LibraryEntry> findOneById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select entry from LibraryEntry entry where entry.id = :id")
    Optional<LibraryEntry> findForUpdate(@Param("id") UUID id);

    @Modifying
    @Query("delete from LibraryEntry entry where entry.id = :id")
    int deleteEntry(@Param("id") UUID id);
}
