package service;

import iotruleservice.*;
import lombok.extern.slf4j.Slf4j;
import spring.annotation.RemoteServiceReference;

import java.util.HashMap;
import java.util.Map;

@RemoteServiceReference // Registers and publishes the annotated service.
@Slf4j
public class userServiceImpl implements IUserService {
    @Override
    public String getUser(String userId) {
        User user = new User("1001","xiaochen","7");
        User user2 = new User("1002","xiaozhao","8");
        Map<String,User> allUserMap =  new HashMap<String,User>();
        allUserMap.put(user.getUserId(),user);
        allUserMap.put(user2.getUserId(),user2);
        return allUserMap.get(userId).toString();
    }

    @Override
    public Map<String, User> getUsers() {
        User user = new User("1001","xiaochen","7");
        User user2 = new User("1002","xiaozhao","8");
        Map<String,User> allUserMap =  new HashMap<String,User>();
        allUserMap.put(user.getUserId(),user);
        allUserMap.put(user2.getUserId(),user2);
        return allUserMap;
    }
}
