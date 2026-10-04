package com.jsf.cricket.repository;

import com.jsf.cricket.domain.Innings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InningsRepository extends JpaRepository<Innings, Long> {
}
