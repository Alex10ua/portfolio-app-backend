package com.dev.alex.Controller;

import com.dev.alex.Model.Users;
import com.dev.alex.Repository.UsersRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

    @Autowired
    private UsersRepository usersRepository;

    @PostMapping("/createUser")
    public Users creteNewUser(@RequestBody Users user){
        var uuid = UUID.randomUUID().toString();
        user.setUserId(uuid.concat(user.getUsername()));
        return usersRepository.save(user);
    }


}
