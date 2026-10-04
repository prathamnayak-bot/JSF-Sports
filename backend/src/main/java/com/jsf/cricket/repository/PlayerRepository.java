package com.jsf.cricket.repository;

import com.jsf.cricket.domain.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByCricsheetId(String cricsheetId);

    List<Player> findTop20ByNameContainingIgnoreCaseOrderByName(String name);
}
