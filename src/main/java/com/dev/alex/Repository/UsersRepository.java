package com.dev.alex.Repository;

import com.dev.alex.Model.Users;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UsersRepository extends MongoRepository<Users, String> {

}
