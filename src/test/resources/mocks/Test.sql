-- Orders placed by the customers.
CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    customer VARCHAR(100) NOT NULL,
    total DECIMAL(10, 2)
);

CREATE INDEX idx_orders_customer ON orders (customer);

INSERT INTO orders (id, customer, total) VALUES (1, 'acme', 10.50);
