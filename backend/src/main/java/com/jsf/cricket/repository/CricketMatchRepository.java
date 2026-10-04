package com.jsf.cricket.repository;

import com.jsf.cricket.domain.CricketMatch;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CricketMatchRepository extends JpaRepository<CricketMatch, Long> {
    boolean existsByCricsheetId(String cricsheetId);

    @EntityGraph(attributePaths = {"venue", "team1", "team2", "winner"})
    List<CricketMatch> findAllByOrderByMatchDateDesc(Pageable pageable);
}
