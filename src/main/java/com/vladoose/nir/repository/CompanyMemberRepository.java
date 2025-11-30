package com.vladoose.nir.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.vladoose.nir.entity.CompanyMember;
import com.vladoose.nir.entity.CompanyMemberId;
public interface CompanyMemberRepository extends JpaRepository<CompanyMember, CompanyMemberId> {}
