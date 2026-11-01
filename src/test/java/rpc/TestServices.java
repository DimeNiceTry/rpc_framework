package rpc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TestServices {
    interface CalculatorService {
        int add(int a, int b);
        int multiply(int a, int b);
        String echo(String message);
    }

    static class CalculatorServiceImpl implements CalculatorService {
        public int add(int a, int b) { return a + b; }
        public int multiply(int a, int b) { return a * b; }
        public String echo(String message) { return message; }
    }

    record User(long id, String name, int age) {}

    interface UserService {
        User findById(long id);
        List<User> findAll();
        List<User> findByAge(int age);
        Map<String, User> findByDepartment();
        void saveAll(List<User> users);
        void delete(long id);
    }

    static class UserServiceImpl implements UserService {
        private final Map<Long, User> users = new LinkedHashMap<>();

        public User findById(long id) {
            return users.get(id);
        }

        public List<User> findAll() {
            return List.copyOf(users.values());
        }

        public List<User> findByAge(int age) {
            return users.values().stream().filter(user -> user.age() == age).toList();
        }

        public Map<String, User> findByDepartment() {
            if (users.isEmpty()) {
                return Map.of();
            }
            return Map.of("IT", users.values().iterator().next());
        }

        public void saveAll(List<User> users) {
            for (User user : users) {
                this.users.put(user.id(), user);
            }
        }

        public void delete(long id) {
            users.remove(id);
        }
    }
}
