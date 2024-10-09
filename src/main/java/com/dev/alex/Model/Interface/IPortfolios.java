package com.dev.alex.Model.Interface;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.dev.alex.Model.Portfolios;
import com.dev.alex.Model.Users;
import java.util.List;

public interface IPortfolios extends MongoRepository<Portfolios, String> {

    @Query("{userId: '?0'}")
    List<Portfolios> findByUserId(Users userId);
}
