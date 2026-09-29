package com.example.testproject.repository;

import com.example.testproject.model.Boat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BoatRepository extends JpaRepository<Boat, Long> {

    List<Boat> findAllByWorldId(Long worldId);
}
