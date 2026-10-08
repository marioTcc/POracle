export interface Order {
    id: string;
    total: number;
}

export type OrderFilter = (order: Order) => boolean;

export enum OrderStatus {
    Open = 'OPEN',
    Shipped = 'SHIPPED',
}

export abstract class OrderSource {
    abstract load(): Promise<Order[]>;
}

export class OrderClient extends OrderSource {
    private readonly retries = 3;

    constructor(private readonly baseUrl: string) {
        super();
    }

    async load(): Promise<Order[]> {
        const response = await fetch(this.baseUrl + '/orders');
        return response.json();
    }
}

export function totalOf(orders: Order[]): number {
    return orders.reduce((sum, order) => sum + order.total, 0);
}
