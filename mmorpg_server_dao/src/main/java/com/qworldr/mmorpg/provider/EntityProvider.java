package com.qworldr.mmorpg.provider;

import com.qworldr.mmorpg.entity.IEntity;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 数据库实体提供接口
 *
 * @param <T>
 * @param <ID>
 * @author wujiazhen
 */
@Repository
public interface EntityProvider<T extends IEntity, ID> extends DataProvider<T, ID> {
    T get(ID id);

    List<T> load();

    T loadAndCreate(ID id, ICreator<T,ID> creator);

    void save(T entity);

    void update(T entity);

    void delete(T entity);

    void delete(ID id);

    List<T> query(String sql, Object... params);

    T queryForSingle(String nameQuery, Object... params);

    <E> List<E> query(String nameQuery, Class<E> clazz, Object... params);

    <E> E queryForSingle(String sql, Class<E> clazz, Object ...params);
}
