package com.example.testproject.repository;

import com.example.testproject.model.PlayerVitals;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerVitalsRepository extends JpaRepository<PlayerVitals, Long> {

    Optional<PlayerVitals> findByWorldIdAndUserId(Long worldId, Long userId);

    List<PlayerVitals> findAllByWorldId(Long worldId);
}
