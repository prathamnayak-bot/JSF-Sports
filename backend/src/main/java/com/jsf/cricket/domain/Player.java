package com.jsf.cricket.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "player")
@Getter
@Setter
@NoArgsConstructor
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable id from Cricsheet's people registry. */
    @Column(unique = true)
    private String cricsheetId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private PlayerRole role;

    private String battingStyle;
    private String bowlingStyle;

    public Player(String cricsheetId, String name) {
        this.cricsheetId = cricsheetId;
        this.name = name;
    }
}
