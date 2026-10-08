const DEFAULT_TIMEOUT = 5000;

// Fetches the orders of a customer.
function fetchOrders(customerId) {
    return fetch('/orders?customer=' + customerId).then(response => response.json());
}

class OrderView {
    static separator = ', ';

    constructor(element) {
        this.element = element;
    }

    render(orders) {
        this.element.innerHTML = orders.map(order => order.id).join(', ');
    }
}

export const formatPrice = (price) => price.toFixed(2) + ' EUR';

export default { fetchOrders, OrderView };

document.addEventListener('DOMContentLoaded', () => new OrderView(document.body).render([]));
