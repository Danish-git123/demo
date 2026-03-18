package com.example.demo.repository;

import com.example.demo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    // Find user by their Supabase UUID (from JWT "sub" claim)
    Optional<User> findBySupabaseId(String supabaseId);

    // Find user by email
    Optional<User> findByEmail(String email);

    // Check if a user already exists for this Supabase ID
    boolean existsBySupabaseId(String supabaseId);
}
