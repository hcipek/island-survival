package com.example.testproject.repository;

import com.example.testproject.model.World;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorldRepository extends JpaRepository<World, Long> {
}
