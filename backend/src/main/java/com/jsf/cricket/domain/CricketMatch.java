package com.jsf.cricket.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "cricket_match")
@Getter
@Setter
@NoArgsConstructor
public class CricketMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String cricsheetId;

    /** T20, ODI, TEST, IT20, ODM or MDM (as in Cricsheet's match_type). */
    @Column(nullable = false)
    private String format;

    private String eventName;
    private String season;

    @Column(nullable = false)
    private LocalDate matchDate;

    @ManyToOne(fetch = FetchType.LAZY)
    private Venue venue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Team team1;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Team team2;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team tossWinner;

    private String tossDecision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_team_id")
    private Team winner;

    private Integer resultMargin;
    private String resultType;

    @ManyToOne(fetch = FetchType.LAZY)
    private Player playerOfMatch;
}
