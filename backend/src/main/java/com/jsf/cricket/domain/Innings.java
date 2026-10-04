package com.jsf.cricket.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "innings")
@Getter
@Setter
@NoArgsConstructor
public class Innings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private CricketMatch match;

    private int inningsNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Team battingTeam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Team bowlingTeam;

    private int totalRuns;
    private int totalWickets;
    private int legalBalls;
}
